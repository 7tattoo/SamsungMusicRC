package com.spotify.music.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * 均衡器共享状态（同进程单例）。
 * UI 线程写参数（touch() 递增版本号），音频线程检测版本变化后重算滤波器系数。
 * 同时承载「输出」页需要的实时 PCM 参数（由 AudioProcessor onConfigure 填充）。
 */
object EqState {
    const val BAND_COUNT = 10
    /** 10 段中心频率 */
    val BAND_FREQS = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    @Volatile var enabled: Boolean = false
    /** 总增益（dB），应用在全部滤波之后 */
    @Volatile var preampDb: Float = 0f
    /** 低音增强（dB），100Hz 低架滤波器 */
    @Volatile var bassBoostDb: Float = 0f
    /** 高音增强（dB），6kHz 高架滤波器 */
    @Volatile var trebleDb: Float = 0f
    /** 环绕增强（0~1，mid/side + 短延迟展宽） */
    @Volatile var width: Float = 0f
    /** 10 段增益（dB），-12 ~ +12 */
    @Volatile var bandGainsDb: FloatArray = FloatArray(BAND_COUNT)

    /** 参数版本号：任何修改后 +1，音频线程据此重算系数 */
    @Volatile var version: Int = 0
    fun touch() { version++ }

    fun setAll(enabled: Boolean, preamp: Float, bass: Float, treble: Float, width: Float, bands: FloatArray) {
        this.enabled = enabled
        this.preampDb = preamp
        this.bassBoostDb = bass
        this.trebleDb = treble
        this.width = width
        System.arraycopy(bands, 0, bandGainsDb, 0, BAND_COUNT)
        touch()
    }

    fun isBypassing(): Boolean {
        if (!enabled) return true
        if (preampDb == 0f && bassBoostDb == 0f && trebleDb == 0f && width == 0f) {
            for (g in bandGainsDb) if (g != 0f) return false
            return true
        }
        return false
    }

    // ── 实时输出参数（「输出」页展示用，音频线程写入） ──
    @Volatile var outSampleRate: Int = 0
    @Volatile var outChannelCount: Int = 0
    @Volatile var outEncoding: Int = 0
    @Volatile var outDeviceId: Int = -1

    fun encodingLabel(): String = when (outEncoding) {
        C.ENCODING_PCM_16BIT -> "16 bit"
        C.ENCODING_PCM_24BIT -> "24 bit"
        C.ENCODING_PCM_32BIT -> "32 bit"
        C.ENCODING_PCM_FLOAT -> "32 bit float"
        C.ENCODING_INVALID -> "未初始化"
        else -> "其他"
    }

    /** 预设（每项 10 个频段的 dB 值） */
    val PRESETS: List<Pair<String, FloatArray>> = listOf(
        "平直" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        "流行" to floatArrayOf(-1f, 1f, 3f, 4f, 3f, 1f, -1f, -1f, -1f, -1f),
        "摇滚" to floatArrayOf(5f, 4f, 1f, -3f, -2f, 1f, 4f, 5f, 5f, 4f),
        "爵士" to floatArrayOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
        "古典" to floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, -4f, -4f, -4f, -5f),
        "电子" to floatArrayOf(5f, 4f, 1f, -2f, 2f, 3f, 0f, -2f, 3f, 5f),
        "人声" to floatArrayOf(-2f, -1f, 0f, 2f, 4f, 4f, 2f, 1f, 0f, -1f),
    )
}

/**
 * 软件参数均衡器（Media3 AudioProcessor，跑在音频播放线程，PCM 直采直写）。
 *
 * 结构：10 段 RBJ peaking biquad（31Hz~16kHz）+ 100Hz 低架（低音增强）+ 总增益 + 软限幅。
 * 参考 Halcyon / RawS Music 的软件 EQ 思路（Apache-2.0），实现为本项目的独立 Kotlin 版本。
 * 支持 16/24/32-bit 整型与 float PCM；参数经 @Volatile 热更新，无需重建播放器。
 */
@UnstableApi
class EqualizerProcessor : BaseAudioProcessor() {

    /** 单个二阶节（转置直接 II 型，每声道 2 个状态变量） */
    private class Section {
        var b0 = 1f; var b1 = 0f; var b2 = 0f
        var a1 = 0f; var a2 = 0f
        // DF2T 状态：s1, s2，按声道分离
        var s1a = 0f; var s2a = 0f
        var s1b = 0f; var s2b = 0f
        var s1c = 0f; var s2c = 0f
        var s1d = 0f; var s2d = 0f

        inline fun reset() {
            s1a = 0f; s2a = 0f; s1b = 0f; s2b = 0f; s1c = 0f; s2c = 0f; s1d = 0f; s2d = 0f
        }

        inline fun process(x: Float, ch: Int): Float {
            // A single unstable/non-finite sample must not poison the biquad state.
            // Once DF2T state becomes NaN, every following sample becomes NaN and
            // integer sinks turn it into zero, which sounds like an immediate mute.
            if (!x.isFinite()) {
                reset()
                return 0f
            }
            val y = b0 * x + when (ch) {
                0 -> s1a
                1 -> s1b
                2 -> s1c
                else -> s1d
            }
            val s2new = b2 * x - a2 * y
            val s1new = b1 * x - a1 * y + s2new
            if (!y.isFinite() || !s1new.isFinite() || !s2new.isFinite() ||
                kotlin.math.abs(y) > 16f || kotlin.math.abs(s1new) > 16f || kotlin.math.abs(s2new) > 16f
            ) {
                reset()
                return x.coerceIn(-1f, 1f)
            }
            when (ch) {
                0 -> { s1a = s1new; s2a = s2new }
                1 -> { s1b = s1new; s2b = s2new }
                2 -> { s1c = s1new; s2c = s2new }
                else -> { s1d = s1new; s2d = s2new }
            }
            return y
        }
    }

    private val sections: Array<Section> = Array(EqState.BAND_COUNT + 2) { Section() } // +低音/高音架
    private var masterGain = 1f
    private var coeffVersion = -1
    private var coeffSampleRate = -1

    private val bassSection: Section get() = sections[EqState.BAND_COUNT]
    private val trebleSection: Section get() = sections[EqState.BAND_COUNT + 1]

    // ── 环绕增强：mid/side + ~12ms 短延迟（Haas）展宽，仅立体声生效 ──
    private var delayL = FloatArray(DELAY_SAMPLES)
    private var delayR = FloatArray(DELAY_SAMPLES)
    private var delayIdx = 0
    private var chCountCached = 2

    private fun widen(x: Float, ch: Int): Float {
        val w = EqState.width.coerceIn(0f, 1f)  // safety clamp — old settings may have >1
        if (w <= 0f || chCountCached != 2) return x
        val self = if (ch == 0) delayL else delayR
        val other = if (ch == 0) delayR else delayL
        val dSelf = self[delayIdx]
        val dOther = other[delayIdx]
        val y = x + w * (dOther - dSelf)
        self[delayIdx] = x
        if (ch == 1) delayIdx = (delayIdx + 1) % DELAY_SAMPLES
        return y
    }

    private fun resetWiden() {
        java.util.Arrays.fill(delayL, 0f)
        java.util.Arrays.fill(delayR, 0f)
        delayIdx = 0
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_24BIT &&
            enc != C.ENCODING_PCM_32BIT && enc != C.ENCODING_PCM_FLOAT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // 记录实时输出参数，供「输出」页展示
        EqState.outSampleRate = inputAudioFormat.sampleRate
        EqState.outChannelCount = inputAudioFormat.channelCount
        EqState.outEncoding = enc
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        if (EqState.isBypassing()) {
            // 原样拷贝（TeeAudioProcessor 同款模式）：写完必须 flip，否则 pipeline 的
            // buffer 状态错乱，甚至会把我们自己的输出 buffer 当输入传回来（自引用崩溃）
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }

        val sampleRate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        val encoding = inputAudioFormat.encoding
        val version = EqState.version
        if (version != coeffVersion || sampleRate != coeffSampleRate) {
            recompute(sampleRate)
            coeffVersion = version
            coeffSampleRate = sampleRate
        }

        val out = replaceOutputBuffer(remaining)
        chCountCached = channels
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> processFloat(inputBuffer, out, channels)
            C.ENCODING_PCM_16BIT -> process16(inputBuffer, out, channels)
            C.ENCODING_PCM_24BIT -> process24(inputBuffer, out, channels)
            C.ENCODING_PCM_32BIT -> process32(inputBuffer, out, channels)
        }
        out.flip()
    }

    // ── 各 PCM 编码的处理循环 ──

    private fun process16(input: ByteBuffer, out: ByteBuffer, channels: Int) {
        val n = input.remaining() / 2
        for (i in 0 until n) {
            val s = input.short.toFloat() / 32768f
            val y = widen(eqSample(s, i % channels), i % channels)
            out.putShort((softClip(y * masterGain) * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
    }

    private fun process24(input: ByteBuffer, out: ByteBuffer, channels: Int) {
        val n = input.remaining() / 3
        val b = 1 shl 23
        for (i in 0 until n) {
            val p = input.position()
            var v = (input.get(p).toInt() and 0xFF) or
                    ((input.get(p + 1).toInt() and 0xFF) shl 8) or
                    ((input.get(p + 2).toInt() and 0xFF) shl 16)
            if (v and 0x800000 != 0) v -= 1 shl 24
            input.position(p + 3)
            val y = widen(eqSample(v.toFloat() / b, i % channels), i % channels)
            var o = (softClip(y * masterGain) * b).toInt()
            if (o > (1 shl 23) - 1) o = (1 shl 23) - 1
            if (o < -(1 shl 23)) o = -(1 shl 23)
            out.put((o and 0xFF).toByte()).put(((o shr 8) and 0xFF).toByte()).put(((o shr 16) and 0xFF).toByte())
        }
    }

    private fun process32(input: ByteBuffer, out: ByteBuffer, channels: Int) {
        val n = input.remaining() / 4
        for (i in 0 until n) {
            val s = input.int.toFloat() / 2147483648L.toFloat()
            val y = widen(eqSample(s, i % channels), i % channels)
            val o = (softClip(y * masterGain) * 2147483647L.toFloat()).toLong().coerceIn(-2147483648L, 2147483647L)
            out.putInt(o.toInt())
        }
    }

    private fun processFloat(input: ByteBuffer, out: ByteBuffer, channels: Int) {
        val fb = input.asFloatBuffer()
        val ob = out.asFloatBuffer()
        val n = fb.remaining()
        for (i in 0 until n) {
            ob.put(softClip(widen(eqSample(fb.get(), i % channels), i % channels) * masterGain))
        }
        input.position(input.position() + n * 4)
        // FloatBuffer 视图不推进 ByteBuffer 位置，需手动同步后再由调用方 flip
        out.position(out.position() + n * 4)
    }

    /** 对单个样本跑一遍滤波链（ch 用于选择声道状态） */
    private fun eqSample(x: Float, ch: Int): Float {
        var y = x
        for (sec in sections) y = sec.process(y, ch)
        return y
    }

    /** 削波保护：接近满幅时软压缩，避免正增益爆音 */
    private fun softClip(x: Float): Float {
        // A malformed/unstable biquad must never turn into NaN. NaN comparisons
        // otherwise fall through and is converted to zero by PCM integer sinks.
        val finite = if (x.isFinite()) x else 0f
        if (finite > 0.96f) return 0.96f + 0.04f * tanh((finite - 0.96f) * 12f)
        if (finite < -0.96f) return -0.96f + 0.04f * tanh((finite + 0.96f) * 12f)
        return finite.coerceIn(-1f, 1f)
    }

    /** 依据当前参数与采样率重算全部滤波器系数 */
    private fun recompute(sampleRate: Int) {
        val sr = sampleRate.coerceAtLeast(8000)
        for (b in 0 until EqState.BAND_COUNT) {
            val gain = EqState.bandGainsDb[b]
            if (gain == 0f) {
                identity(sections[b])
            } else {
                peaking(sections[b], EqState.BAND_FREQS[b], sr, gain)
            }
        }
        val bass = EqState.bassBoostDb
        if (bass == 0f) identity(bassSection) else lowShelf(bassSection, 100f, sr, bass)
        val treble = EqState.trebleDb
        if (treble == 0f) identity(trebleSection) else highShelf(trebleSection, 6000f, sr, treble)
        masterGain = Math.pow(10.0, EqState.preampDb / 20.0).toFloat()
    }

    private fun identity(s: Section) {
        s.b0 = 1f; s.b1 = 0f; s.b2 = 0f; s.a1 = 0f; s.a2 = 0f
        s.reset()
    }

    /** RBJ peaking EQ */
    private fun peaking(s: Section, f0: Float, fs: Int, gainDb: Float) {
        val a = Math.pow(10.0, gainDb / 40.0).toFloat()
        // 16 kHz is close to Nyquist on 44.1/48 kHz material. A narrow/high-Q
        // peaking section there is extremely sensitive to quantisation and can ring
        // audibly. Keep the control but use a safer effective corner and bandwidth.
        val isUltraHighBand = f0 >= 15000f
        val maxNormalizedFrequency = if (isUltraHighBand) 0.30f else 0.45f
        val safeF0 = f0.coerceIn(10f, fs * maxNormalizedFrequency)
        val w0 = (2.0 * PI * safeF0 / fs).toFloat()
        val cw = cos(w0)
        val sw = sin(w0)
        val q = if (isUltraHighBand) 0.7f else 1.1f
        val alpha = sw / (2f * q)
        val a0 = 1f + alpha / a
        s.b0 = (1f + alpha * a) / a0
        s.b1 = (-2f * cw) / a0
        s.b2 = (1f - alpha * a) / a0
        s.a1 = (-2f * cw) / a0
        s.a2 = (1f - alpha / a) / a0
        s.reset()
    }

    /** RBJ low shelf（低音增强） */
    private fun lowShelf(s: Section, f0: Float, fs: Int, gainDb: Float) {
        val a = Math.pow(10.0, gainDb / 40.0).toFloat()
        val safeF0 = f0.coerceIn(10f, fs * 0.45f)
        val w0 = (2.0 * PI * safeF0 / fs).toFloat()
        val cw = cos(w0)
        val sw = sin(w0)
        val sqrtA = sqrt(a)
        val alpha = sw / 2f * sqrt(2f)
        val twoSqrtAAlpha = 2f * sqrtA * alpha
        val a0 = (a + 1f) + (a - 1f) * cw + twoSqrtAAlpha
        s.b0 = (a * ((a + 1f) - (a - 1f) * cw + twoSqrtAAlpha)) / a0
        s.b1 = (2f * a * ((a - 1f) - (a + 1f) * cw)) / a0
        s.b2 = (a * ((a + 1f) - (a - 1f) * cw - twoSqrtAAlpha)) / a0
        s.a1 = (-2f * ((a - 1f) + (a + 1f) * cw)) / a0
        s.a2 = ((a + 1f) + (a - 1f) * cw - twoSqrtAAlpha) / a0
        s.reset()
    }

    /** RBJ high shelf（高音增强） */
    private fun highShelf(s: Section, f0: Float, fs: Int, gainDb: Float) {
        val a = Math.pow(10.0, gainDb / 40.0).toFloat()
        val safeF0 = f0.coerceIn(10f, fs * 0.45f)
        val w0 = (2.0 * PI * safeF0 / fs).toFloat()
        val cw = cos(w0)
        val sw = sin(w0)
        val sqrtA = sqrt(a)
        val alpha = sw / 2f * sqrt(2f)
        val twoSqrtAAlpha = 2f * sqrtA * alpha
        val a0 = (a + 1f) - (a - 1f) * cw + twoSqrtAAlpha
        s.b0 = (a * ((a + 1f) + (a - 1f) * cw + twoSqrtAAlpha)) / a0
        s.b1 = (-2f * a * ((a - 1f) + (a + 1f) * cw)) / a0
        s.b2 = (a * ((a + 1f) + (a - 1f) * cw - twoSqrtAAlpha)) / a0
        s.a1 = (2f * ((a - 1f) - (a + 1f) * cw)) / a0
        s.a2 = ((a + 1f) - (a - 1f) * cw - twoSqrtAAlpha) / a0
        s.reset()
    }

    override fun onFlush() {
        sections.forEach { it.reset() }
        resetWiden()
    }

    override fun onReset() {
        sections.forEach { it.reset() }
        resetWiden()
        coeffVersion = -1
        coeffSampleRate = -1
    }

    private companion object {
        /** 环绕增强延迟线长度（约 12ms @44.1kHz） */
        const val DELAY_SAMPLES = 512
    }
}
