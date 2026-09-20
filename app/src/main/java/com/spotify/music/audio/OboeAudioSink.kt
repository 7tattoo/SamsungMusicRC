package com.spotify.music.audio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import com.google.common.collect.ImmutableList
import com.spotify.music.util.CrashLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A Media3 [AudioSink] that outputs decoded PCM through a native Oboe stream (AAudio / OpenSL ES)
 * instead of the Java AudioTrack used by DefaultAudioSink. The configured [processors] (EQ, output
 * format conversion) run inside an [AudioProcessingPipeline] before the PCM is handed to Oboe.
 *
 * This is a first, functional implementation; playback-speed changes and per-sink volume are not
 * applied to the Oboe stream yet (system volume still works), and it needs on-device tuning.
 *
 * [audioApi]: 1 = AAudio, 2 = OpenSL ES. [exclusive]: request exclusive sharing mode (USB / bit-perfect).
 */
@UnstableApi
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class OboeAudioSink(
    private val audioApi: Int = AUDIO_API_AAUDIO,
    private val exclusive: Boolean = false,
    processors: List<AudioProcessor> = emptyList(),
    private val deviceId: Int = 0
) : AudioSink {

    private val pipeline = AudioProcessingPipeline(ImmutableList.copyOf(processors))

    private var listener: AudioSink.Listener? = null
    private var oboe: OboeAudioOutput? = null

    private var configuredFormat: Format? = null
    private var outputSampleRate = 0
    private var outputChannelCount = 0
    private var outputEncoding = C.ENCODING_PCM_16BIT
    private var outputFrameSize = 4
    private var oboeEncodingId = 0

    private var pendingOutput: ByteBuffer? = null
    private var directScratch: ByteBuffer = EMPTY

    private var startMediaTimeUs = C.TIME_UNSET
    private var framesSubmitted = 0L
    private var inputEnded = false

    // getFramesRead() 是流创建以来的绝对帧数：换歌复用的是同一条流，
    // 位置/待播判断必须减去本次会话的帧基线，否则会叠加上一首的帧数。
    private var frameBase = 0L
    // 流实际采样率：Oboe 可能按设备原生率重采样，位置换算以它为准而非请求值。
    private var actualSampleRate = 0
    // 连续写阻塞计数：超时/写 0 字节都是瞬态，连拍多下无进展才重开流。
    private var stallCount = 0

    /** 播放器意图是否为播放。暂停期间禁止一切 stall 触发的流重开（否则暂停会被自己复活）。 */
    @Volatile private var streamActive = false

    // 暂停诊断：记录暂停瞬间设备已消费的帧数与时刻，下次 play() 时对比，
    // 用于判断「点暂停后音乐还在放」是原生流没停下，还是播放又被人重新拉起。
    private var framesAtPause = 0L
    private var pausedAtMs = 0L

    private var volume = 1f
    private var audioAttributes = AudioAttributes.DEFAULT
    private var playbackParameters = PlaybackParameters.DEFAULT
    private var skipSilenceEnabled = false

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        if (MimeTypes.AUDIO_RAW == format.sampleMimeType && Util.isEncodingLinearPcm(format.pcmEncoding)) {
            AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        } else {
            AudioSink.SINK_FORMAT_UNSUPPORTED
        }

    @Throws(AudioSink.ConfigurationException::class)
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        configuredFormat = inputFormat
        val inputAudioFormat = AudioProcessor.AudioFormat(
            inputFormat.sampleRate,
            inputFormat.channelCount,
            inputFormat.pcmEncoding
        )
        val outputAudioFormat = try {
            val configured = pipeline.configure(inputAudioFormat)
            pipeline.flush()
            if (pipeline.isOperational) configured else inputAudioFormat
        } catch (e: AudioProcessor.UnhandledAudioFormatException) {
            throw AudioSink.ConfigurationException(e, inputFormat)
        }

        val newSampleRate = outputAudioFormat.sampleRate
        val newChannelCount = outputAudioFormat.channelCount
        val newEncoding = outputAudioFormat.encoding
        val newOboeEncodingId = encodingToOboeId(newEncoding)
        val newFrameSize = newChannelCount * bytesPerSample(newEncoding)

        // Reuse the existing Oboe stream when the output format hasn't changed.
        // The pipeline (Sonic + OutputFormat) normalises the output, so switching tracks
        // with different source formats usually yields the same output format. Avoiding
        // close/reopen prevents native crashes on devices where rapid AAudio stream
        // recreation kills the process.
        val canReuse = oboe?.isOpen == true &&
            outputSampleRate == newSampleRate &&
            outputChannelCount == newChannelCount &&
            oboeEncodingId == newOboeEncodingId

        outputSampleRate = newSampleRate
        outputChannelCount = newChannelCount
        outputEncoding = newEncoding
        oboeEncodingId = newOboeEncodingId
        outputFrameSize = newFrameSize

        if (canReuse) {
            // Output is fixed-format for the native path. The preceding AudioSink.flush()
            // resets Media3's pipeline; do not call native flush/pause/start here. Repeated
            // native state transitions during a format callback are what make some firmware
            // kill the process after several track changes.
            pendingOutput = null
        } else {
            oboe?.close()
            val output = OboeAudioOutput()
            if (!output.open(audioApi, outputSampleRate, outputChannelCount, oboeEncodingId, exclusive, deviceId)) {
                throw AudioSink.ConfigurationException("Unable to open Oboe output stream", inputFormat)
            }
            oboe = output
            // 流保持未启动：只等 AudioSink.play()（nativeStart）。旧版在这里 start
            // 以便「预缓冲」，但暂停态下的预热写入会直接出声 —— 恢复队列后 ExoPlayer
            // 明明是 paused，音乐却已经在放（自动播放 + UI 卡暂停的另一半真相）。
        }
        actualSampleRate = oboe?.outputSampleRate() ?: newSampleRate
        resetPlaybackState()
        // 诊断锚点：实际流参数（采样率/声道/格式/缓冲）与请求值不一致是变速/欠载的第一嫌疑
        CrashLogger.trace(
            "sink configured api=$audioApi reuse=$canReuse reqRate=$newSampleRate actualRate=$actualSampleRate " +
                "enc=$newOboeEncodingId ch=$newChannelCount exclusive=$exclusive device=$deviceId"
        )
    }

    override fun play() {
        // 暂停期间原生流是否还在消耗帧：delta>0 说明声音其实没停
        if (pausedAtMs != 0L) {
            val delta = ((oboe?.framesRead() ?: 0L) - framesAtPause).coerceAtLeast(0L)
            val gap = android.os.SystemClock.uptimeMillis() - pausedAtMs
            CrashLogger.trace("sink play | paused ${gap}ms ago, framesRead delta while paused=$delta")
        } else {
            CrashLogger.trace("sink play | isOpen=${oboe?.isOpen}")
        }
        streamActive = true
        oboe?.start()
    }

    override fun pause() {
        framesAtPause = oboe?.framesRead() ?: 0L
        pausedAtMs = android.os.SystemClock.uptimeMillis()
        // 先摘掉「活动」标记再暂停：暂停窗口内写入方返回的 0 字节属于正常反压，
        // 不允许触发 reopen（vivo OpenSL HAL requestPause 不可靠，pause 走 stop，
        // 如果 stall 逻辑此刻把流 start 回来，就会表现为「暂停后自动恢复播放」）。
        streamActive = false
        CrashLogger.trace("sink pause | isOpen=${oboe?.isOpen} framesRead=$framesAtPause")
        // Must actually pause the native stream. ExoPlayer calls this when the user taps
        // pause; if we skip it the Oboe stream keeps draining its internal buffer and
        // audio continues even though playWhenReady is false.
        // The crash we saw was in flush() (pause+flush+close/reopen during track change),
        // not in a simple pause. A standalone pause is safe.
        oboe?.pause()
    }

    override fun handleDiscontinuity() {
        startMediaTimeUs = C.TIME_UNSET
        frameBase = oboe?.framesRead() ?: 0L
    }

    @Throws(AudioSink.InitializationException::class, AudioSink.WriteException::class)
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (oboe == null) return false
        if (startMediaTimeUs == C.TIME_UNSET && buffer.hasRemaining()) {
            startMediaTimeUs = presentationTimeUs
        }

        if (pipeline.isOperational) {
            if (!drainPipeline()) return false
            if (buffer.hasRemaining()) pipeline.queueInput(buffer)
            if (!drainPipeline()) return false
            return !buffer.hasRemaining()
        }
        return writeDirect(buffer)
    }

    @Throws(AudioSink.WriteException::class)
    override fun playToEndOfStream() {
        if (!inputEnded) {
            inputEnded = true
            if (pipeline.isOperational) pipeline.queueEndOfStream()
        }
        if (pipeline.isOperational) drainPipeline()
    }

    override fun isEnded(): Boolean = inputEnded && !hasPendingData()

    override fun hasPendingData(): Boolean {
        if (pendingOutput?.hasRemaining() == true) return true
        if (pipeline.isOperational && !pipeline.isEnded()) return true
        val read = (oboe?.framesRead() ?: 0L) - frameBase
        return framesSubmitted > read
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        this.skipSilenceEnabled = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean = skipSilenceEnabled

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        this.audioAttributes = audioAttributes
    }

    override fun getAudioAttributes(): AudioAttributes = audioAttributes

    override fun setAudioSessionId(audioSessionId: Int) {}

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {}

    override fun getAudioTrackBufferSizeUs(): Long = C.TIME_UNSET

    override fun enableTunnelingV21() {}

    override fun disableTunneling() {}

    override fun setVolume(volume: Float) {
        this.volume = volume
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val output = oboe ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (startMediaTimeUs == C.TIME_UNSET || actualSampleRate <= 0) {
            return AudioSink.CURRENT_POSITION_NOT_SET
        }
        val framesRead = output.framesRead()
        return startMediaTimeUs + (framesRead - frameBase) * C.MICROS_PER_SECOND / actualSampleRate
    }

    override fun flush() {
        pipeline.flush()
        pendingOutput = null
        // Native output is kept started for the lifetime of the sink. Do not call Oboe
        // pause/flush here: on vivo firmware those transitions race with ExoPlayer's decoder
        // flush and eventually crash the process or make write() return 0 forever.
        resetPlaybackState()
    }

    override fun reset() {
        pipeline.reset()
        oboe?.close()
        oboe = null
        pendingOutput = null
        configuredFormat = null
        resetPlaybackState()
    }

    private fun resetPlaybackState() {
        startMediaTimeUs = C.TIME_UNSET
        framesSubmitted = 0L
        inputEnded = false
        frameBase = oboe?.framesRead() ?: 0L
        stallCount = 0
        // streamActive 不能在这里重置为 true：恢复队列（configure→reset）发生在
        // ExoPlayer 真正 play() 之前，若此处置 true，暂停态下的预热写入会直接
        // 走进「已启动」的流出声（trace_7：无 play 命令无 sink play 却在响）。
        // 活动标志只由 play()/pause() 驱动；换歌等 mid-playback 场景 play() 已
        // 在播放意图里，状态保持 true 不受影响。
    }

    /** Drains any pending output and everything the pipeline can currently produce. */
    @Throws(AudioSink.WriteException::class)
    private fun drainPipeline(): Boolean {
        while (true) {
            val pending = pendingOutput
            if (pending != null && pending.hasRemaining()) {
                if (!writeToOboe(pending)) return false
            }
            val next = pipeline.getOutput()
            if (!next.hasRemaining()) {
                pendingOutput = null
                return true
            }
            applyGain(next)
            pendingOutput = next
        }
    }

    @Throws(AudioSink.WriteException::class)
    private fun writeDirect(buffer: ByteBuffer): Boolean {
        if (!buffer.hasRemaining()) return true
        // Copy into a direct scratch when the input isn't direct OR when we must apply volume gain
        // (we must never modify the decoder's own buffer in place).
        val needsCopy = !buffer.isDirect || volume != 1f
        val toWrite = if (!needsCopy) {
            buffer
        } else {
            val remaining = buffer.remaining()
            if (directScratch.capacity() < remaining) {
                directScratch = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
            }
            directScratch.clear()
            directScratch.put(buffer.duplicate())
            directScratch.flip()
            applyGain(directScratch)
            directScratch
        }
        val startPos = toWrite.position()
        val done = writeToOboe(toWrite)
        val consumed = toWrite.position() - startPos
        if (needsCopy) {
            buffer.position((buffer.position() + consumed).coerceAtMost(buffer.limit()))
        }
        return done && !buffer.hasRemaining()
    }

    /** Scales the buffer's [position, limit) region in place by [volume], per PCM encoding. */
    private fun applyGain(buffer: ByteBuffer) {
        val v = volume
        if (v == 1f) return
        val previousOrder = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        var pos = buffer.position()
        val limit = buffer.limit()
        when (outputEncoding) {
            C.ENCODING_PCM_16BIT -> while (pos + 2 <= limit) {
                val scaled = (buffer.getShort(pos) * v).toInt().coerceIn(-32768, 32767)
                buffer.putShort(pos, scaled.toShort())
                pos += 2
            }
            C.ENCODING_PCM_32BIT -> while (pos + 4 <= limit) {
                val scaled = (buffer.getInt(pos) * v.toDouble()).toLong()
                    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                buffer.putInt(pos, scaled.toInt())
                pos += 4
            }
            C.ENCODING_PCM_FLOAT -> while (pos + 4 <= limit) {
                buffer.putFloat(pos, (buffer.getFloat(pos) * v).coerceIn(-1f, 1f))
                pos += 4
            }
            C.ENCODING_PCM_24BIT -> while (pos + 3 <= limit) {
                var sample = (buffer.get(pos).toInt() and 0xFF) or
                    ((buffer.get(pos + 1).toInt() and 0xFF) shl 8) or
                    ((buffer.get(pos + 2).toInt() and 0xFF) shl 16)
                if (sample and 0x800000 != 0) sample = sample or -0x1000000
                val scaled = (sample * v).toInt().coerceIn(-8388608, 8388607)
                buffer.put(pos, (scaled and 0xFF).toByte())
                buffer.put(pos + 1, ((scaled shr 8) and 0xFF).toByte())
                buffer.put(pos + 2, ((scaled shr 16) and 0xFF).toByte())
                pos += 3
            }
        }
        buffer.order(previousOrder)
    }

    @Throws(AudioSink.WriteException::class)
    private fun writeToOboe(buffer: ByteBuffer): Boolean {
        val output = oboe ?: return false
        if (!buffer.hasRemaining()) return true
        val n = output.write(buffer, buffer.position(), buffer.remaining(), WRITE_TIMEOUT_NS)
        if (n == -2) {
            // 设备断开（后台播放/切歌时 HAL 收回流）。原地重开一条同参新流，
            // 上层随即重试同一块缓冲；只有重开失败才抛「可恢复」异常，
            // 绝不让一次断开变成 Media3 的致命错误把播放器打进 IDLE。
            if (reopenStream()) return false
            throw AudioSink.WriteException(n, configuredFormat ?: Format.Builder().build(), true)
        }
        if (n < 0 || (n == 0 && buffer.remaining() >= outputFrameSize)) {
            val stall = ++stallCount
            // ExoPlayer 暂停后仍会继续解码喂缓冲；流已 stop 时 write 必然 0 字节，
            // 这不是「流楔死」。绝不能在暂停态重开/start 流——否则就是
            // 「手动暂停后，播放自己又复活」的 bug（trace: 暂停 8s 后 reopened）。
            if (!streamActive) return false
            // 写超时 / 缓冲满：瞬态，让上层重试。连续多拍无进展则重开流防楔死。
            if (stall >= MAX_CONSECUTIVE_STALLS) {
                stallCount = 0
                if (!reopenStream()) {
                    throw AudioSink.WriteException(n, configuredFormat ?: Format.Builder().build(), false)
                }
            }
            // 限频诊断：欠载时刻的积压帧数 + 请求/实际采样率。
            // gap 持续增长 = 设备消费慢于喂入（时钟失配/固件重采样）；忽大忽小 = 喂入线程瞬停（GC/调度）。
            if (stall <= 3 || stall % 10 == 1) {
                val pending = framesSubmitted - ((output.framesRead() - frameBase).coerceAtLeast(0L))
                CrashLogger.trace(
                    "sink stall result=$n pendingFrames=$pending reqRate=$outputSampleRate " +
                        "actualRate=$actualSampleRate api=$audioApi stall=$stall"
                )
            }
            return false
        }
        stallCount = 0
        if (n == 0) {
            // 剩余不足一帧的尾部字节，直接消费掉，避免无限重试
            buffer.position(buffer.limit())
            return true
        }
        if (outputFrameSize > 0) framesSubmitted += n / outputFrameSize
        buffer.position((buffer.position() + n).coerceAtMost(buffer.limit()))
        return !buffer.hasRemaining()
    }

    /**
     * 断开/楔死后重开一条同参 Oboe 流。只应在写入路径（播放线程）调用；
     * 重开成功后把帧基线清零，位置换算以新流为准。
     */
    private fun reopenStream(): Boolean {
        val old = oboe
        runCatching { old?.close() }
        return try {
            val output = OboeAudioOutput()
            if (!output.open(audioApi, outputSampleRate, outputChannelCount, oboeEncodingId, exclusive, deviceId)) {
                CrashLogger.log(
                    IllegalStateException("Oboe reopen failed"),
                    "OboeAudioSink.reopenStream | rate=$outputSampleRate ch=$outputChannelCount enc=$oboeEncodingId",
                )
                false
            } else {
                oboe = output
                actualSampleRate = output.outputSampleRate()
                frameBase = 0L
                stallCount = 0
                // 重开只发生在写入路径的播放中（streamActive=true 才会走到 stall），
                // 按当前意图直接启动，恢复被断掉的输出。
                if (streamActive) output.start()
                CrashLogger.trace(
                    "sink reopened ok rate=$outputSampleRate actualRate=$actualSampleRate api=$audioApi started=$streamActive"
                )
                true
            }
        } catch (t: Throwable) {
            CrashLogger.log(t, "OboeAudioSink.reopenStream")
            false
        }
    }

    private fun encodingToOboeId(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 0
        C.ENCODING_PCM_24BIT -> 1
        C.ENCODING_PCM_32BIT -> 2
        C.ENCODING_PCM_FLOAT -> 3
        else -> 0
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 2
    }

    companion object {
        const val AUDIO_API_AUTO = 0
        const val AUDIO_API_AAUDIO = 1
        const val AUDIO_API_OPENSLES = 2
        // Bound blocking writes so a stalled/disconnected device can't wedge the audio thread.
        private const val WRITE_TIMEOUT_NS = 200_000_000L
        // 连续写 0 字节/超时多少次后判定流已楔死并重开（每次最多等 200ms，8 拍约 1.6s）。
        private const val MAX_CONSECUTIVE_STALLS = 8
        private val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
