package com.spotify.music.audio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioSink.AudioSinkConfig
import androidx.media3.exoplayer.audio.AudioSink.InitializationException
import androidx.media3.exoplayer.audio.AudioSink.Listener
import androidx.media3.exoplayer.audio.AudioSink.WriteException
import androidx.media3.exoplayer.audio.AudioSink.CURRENT_POSITION_NOT_SET
import androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
import androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
import androidx.media3.exoplayer.audio.AudioSink.SINK_FORMAT_UNSUPPORTED
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AAudio 原生音频输出（USB DAC 独占模式优先，独占不可用时自动回退共享模式）。
 *
 * 数据链：Media3 播放线程 → PCM 转 float → JNI → 无锁 SPSC 环形缓冲 → AAudio 回调 → DAC。
 *
 * 已知取舍（v1）：
 * - 倍速不生效（恒为 1.0，位置换算按 1.0 对齐，不会错拍）
 * - 跳过静音不生效
 * - 软件均衡器/环绕增强走 DefaultAudioSink 的 AudioProcessor 链，原生模式下不经过，不生效
 */
@UnstableApi
class OboeAudioSink(
    private val preferExclusive: Boolean = true,
    private val deviceId: Int = -1,
) : AudioSink {

    companion object {
        init {
            System.loadLibrary("samsung_audio")
        }
    }

    private var listener: Listener? = null

    // ── 配置状态 ──
    private var pendingFormat: Format? = null
    private var pendingChannelMap: IntArray? = null
    private var configuredFormat: Format? = null
    private var activeChannelMap: IntArray? = null
    private var streamOpened = false
    private var openFailed = false
    private var everStarted = false
    private var ended = false

    /** play() 可能在流打开之前被调（BUFFERING 阶段），打开后据此补启动 */
    private var playRequested = false

    private var volume = 1f
    private var skipSilence = false
    private var attrs: AudioAttributes? = null
    private var lastStatusAt = 0L

    // 转换暂存
    private var scratch = FloatArray(0)

    // ── JNI ──
    private external fun nativeCreateStream(sampleRate: Int, channels: Int, exclusive: Boolean, deviceId: Int): Int
    private external fun nativeStart(): Boolean
    private external fun nativePause(): Boolean
    private external fun nativeFlushStream(): Boolean
    private external fun nativeCloseStream(): Boolean
    private external fun nativeWriteFloats(values: FloatArray, frames: Int): Int
    private external fun nativeFramesWritten(): Long
    private external fun nativeQueuedFrames(): Int
    private external fun nativeFreeFrames(): Int
    private external fun nativeGetXRuns(): Int
    private external fun nativeIsExclusive(): Boolean
    private external fun nativeStreamDead(): Boolean
    private external fun nativeBufferSizeFrames(): Int
    private external fun nativeSetVolume(v: Float)
    private external fun nativeSetEq(enabled: Boolean, preampDb: Float, bassDb: Float, trebleDb: Float, width: Float, bands: FloatArray)

    /**
     * 把 EqState 当前参数推送到原生 DSP。
     * 原生模式下由 PlaybackService 在设置变化/恢复时调用。
     */
    fun pushEq() {
        runCatching {
            nativeSetEq(
                com.spotify.music.audio.EqState.enabled,
                com.spotify.music.audio.EqState.preampDb,
                com.spotify.music.audio.EqState.bassBoostDb,
                com.spotify.music.audio.EqState.trebleDb,
                com.spotify.music.audio.EqState.width,
                com.spotify.music.audio.EqState.bandGainsDb,
            )
        }
    }

    // ── AudioSink 实现 ──

    override fun setListener(listener: Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int {
        val encoding = format.pcmEncoding
        return when {
            format.sampleRate <= 0 || format.channelCount <= 0 -> SINK_FORMAT_UNSUPPORTED
            encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT ->
                SINK_FORMAT_SUPPORTED_DIRECTLY
            encoding == C.ENCODING_PCM_24BIT || encoding == C.ENCODING_PCM_32BIT ->
                SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
            else -> SINK_FORMAT_UNSUPPORTED
        }
    }

    @Deprecated("Deprecated in AudioSink")
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        pendingFormat = inputFormat
        pendingChannelMap = normalizeMap(outputChannels)
    }

    override fun configure(config: AudioSinkConfig) {
        val map = config.outputChannelMapping
        configure(config.format, config.preferredBufferSizeOverride, map?.takeIf { !it.isEmpty }?.toArray())
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        buffer.order(ByteOrder.nativeOrder())
        applyPendingConfig()
        val format = configuredFormat ?: return true // 未配置：按契约丢弃
        if (openFailed) throw WriteException(-2, format, false)
        if (nativeStreamDead()) {
            // 设备被拔掉等致命错误：交回 ExoPlayer 处理
            throw WriteException(-3, format, false)
        }
        if (!streamOpened) openStream(format)
        // play() 可能先于首个 handleBuffer 到来（BUFFERING 阶段被跳过），这里补启动
        if (playRequested && !ended) {
            nativeSetVolume(volume)
            startStream("after-open")
        }

        // 每 5 秒采一次 sink 状态（排查无声/卡缓冲用）
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStatusAt > 5_000) {
            lastStatusAt = now
            com.spotify.music.util.CrashLogger.trace(
                "OboeSink status rate=${format.sampleRate} queued=${nativeQueuedFrames()} " +
                    "written=${nativeFramesWritten()} xrun=${nativeGetXRuns()}"
            )
        }

        val inCh = format.channelCount
        val map = activeChannelMap
        val outCh = map?.size ?: inCh
        val bytesPerFrame = bytesPerSample(format.pcmEncoding) * inCh

        while (buffer.hasRemaining()) {
            val free = nativeFreeFrames()
            if (free <= 0) return false // 环满：让渲染器稍后重试同一 buffer
            val frames = minOf(free, buffer.remaining() / bytesPerFrame)
            if (frames <= 0) return false
            convertChunk(buffer, frames, format.pcmEncoding, inCh, outCh, map)
            val written = nativeWriteFloats(scratch, frames)
            // 单生产者模型下 free 只增不减，正常必然全部写入；万一不足，跳过该帧保持同步
            if (written < frames) {
                com.spotify.music.util.CrashLogger.log(
                    IllegalStateException("ring write short: want=$frames got=$written"), "OboeAudioSink"
                )
            }
        }
        return true
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!streamOpened || !everStarted) return CURRENT_POSITION_NOT_SET
        val frames = nativeFramesWritten()
        if (frames <= 0 && !sourceEnded) return CURRENT_POSITION_NOT_SET
        return frames * 1_000_000L / configuredFormat!!.sampleRate
    }

    override fun play() {
        playRequested = true
        if (!streamOpened || ended) return
        nativeSetVolume(volume)
        startStream("play")
    }

    override fun handleDiscontinuity() {
        // 位置不连续由 flush/reset 处理，无需额外操作
    }

    override fun playToEndOfStream() {
        val format = configuredFormat ?: run {
            ended = true
            return
        }
        var waited = 0L
        while (nativeQueuedFrames() > 0) {
            if (nativeStreamDead()) throw WriteException(-3, format, false)
            if (waited > 30_000) throw WriteException(-4, format, false)
            Thread.sleep(10)
            waited += 10
        }
        ended = true
    }

    override fun isEnded(): Boolean = ended && nativeQueuedFrames() <= 0

    override fun hasPendingData(): Boolean = nativeQueuedFrames() > 0

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        // v1：不应用倍速，getPlaybackParameters 恒回 1.0 保证位置换算一致
    }

    override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters(1f)

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        skipSilence = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilence

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        attrs = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes =
        attrs ?: AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

    override fun setAudioSessionId(audioSessionId: Int) {
        // AAudio 不使用 audioSessionId
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        // 不支持辅助音效
    }

    override fun getAudioTrackBufferSizeUs(): Long {
        if (!streamOpened) return 0L
        val frames = nativeBufferSizeFrames()
        return if (frames > 0) frames * 1_000_000L / configuredFormat!!.sampleRate else 0L
    }

    override fun enableTunnelingV21() {
        // 不支持隧道播放
    }

    override fun disableTunneling() {
        // no-op
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f)
        nativeSetVolume(this.volume)
    }

    override fun pause() {
        playRequested = false
        if (streamOpened) nativePause()
    }

    override fun flush() {
        ended = false
        playRequested = false
        if (streamOpened) nativeFlushStream()
    }

    override fun reset() {
        ended = false
        openFailed = false
        everStarted = false
        playRequested = false
        streamOpened = false
        configuredFormat = null
        activeChannelMap = null
        pendingFormat = null
        pendingChannelMap = null
        nativeCloseStream()
    }

    // ── 内部 ──

    private fun normalizeMap(map: IntArray?): IntArray? =
        if (map == null || map.isEmpty() || map.contentEquals(IntArray(map.size) { it })) null else map

    private fun applyPendingConfig() {
        val pf = pendingFormat ?: return
        val map = pendingChannelMap
        pendingFormat = null
        pendingChannelMap = null
        val needReopen = streamOpened && (
            configuredFormat?.sampleRate != pf.sampleRate ||
                configuredFormat?.channelCount != pf.channelCount ||
                configuredFormat?.pcmEncoding != pf.pcmEncoding
            )
        if (needReopen) {
            nativeCloseStream()
            streamOpened = false
        }
        configuredFormat = pf
        activeChannelMap = map
    }

    private fun openStream(format: Format) {
        val result = nativeCreateStream(format.sampleRate, format.channelCount, preferExclusive, deviceId)
        if (result != 0) {
            openFailed = true
            streamOpened = false
            com.spotify.music.util.CrashLogger.trace(
                "OboeSink open FAILED res=$result rate=${format.sampleRate} ch=${format.channelCount} dev=$deviceId"
            )
            throw InitializationException(
                "AAudio open stream failed: $result (rate=${format.sampleRate} ch=${format.channelCount})",
                0, format, /* isRecoverable = */ false, null,
            )
        }
        streamOpened = true
        openFailed = false
        nativeSetVolume(volume)
        pushEq()
        com.spotify.music.util.CrashLogger.trace(
            "OboeSink opened rate=${format.sampleRate} ch=${format.channelCount} dev=$deviceId"
        )
        // 流一打开，若此前已请求播放则立刻启动，不等下一次 play()
        if (playRequested && !ended) startStream("after-open")
    }

    private fun startStream(why: String) {
        val ok = nativeStart()
        if (ok) {
            everStarted = true
            playRequested = false
        }
        com.spotify.music.util.CrashLogger.trace("OboeSink start($why) ok=$ok")
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        else -> 4
    }

    private fun ensureScratch(size: Int) {
        if (scratch.size < size) scratch = FloatArray(size)
    }

    /** 从 buffer 取 frames 帧，转成 float 写入 scratch（含声道映射） */
    private fun convertChunk(
        buffer: ByteBuffer,
        frames: Int,
        encoding: Int,
        inCh: Int,
        outCh: Int,
        map: IntArray?,
    ) {
        ensureScratch(frames * outCh)
        val tmpIn = FloatArray(frames * inCh)
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                val sb = buffer.asShortBuffer()
                for (i in 0 until frames * inCh) tmpIn[i] = sb.get() / 32768f
                buffer.position(buffer.position() + frames * inCh * 2)
            }
            C.ENCODING_PCM_24BIT -> {
                for (i in 0 until frames * inCh) {
                    var v = (buffer.get().toInt() and 0xFF) or
                        ((buffer.get().toInt() and 0xFF) shl 8) or
                        ((buffer.get().toInt() and 0xFF) shl 16)
                    if (v and 0x800000 != 0) v -= 1 shl 24
                    tmpIn[i] = v.toFloat() / (1 shl 23)
                }
            }
            C.ENCODING_PCM_32BIT -> {
                val ib = buffer.asIntBuffer()
                for (i in 0 until frames * inCh) tmpIn[i] = ib.get() / 2147483648f
                buffer.position(buffer.position() + frames * inCh * 4)
            }
            else -> { // ENCODING_PCM_FLOAT
                val fb = buffer.asFloatBuffer()
                fb.get(tmpIn, 0, frames * inCh)
                buffer.position(buffer.position() + frames * inCh * 4)
            }
        }
        if (map == null && inCh == outCh) {
            System.arraycopy(tmpIn, 0, scratch, 0, frames * outCh)
        } else {
            for (f in 0 until frames) {
                for (o in 0 until outCh) {
                    val s = if (map != null && o < map.size) map[o] else o
                    scratch[f * outCh + o] = tmpIn[f * inCh + s.coerceIn(0, inCh - 1)]
                }
            }
        }
    }
}
