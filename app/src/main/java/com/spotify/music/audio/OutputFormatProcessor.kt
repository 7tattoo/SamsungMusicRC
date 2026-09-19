package com.spotify.music.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts PCM encoding; sample-rate conversion is delegated to Media3 SonicAudioProcessor.
 *
 * 热路径（float↔I16，即 Oboe 管线两端的标准转换）用批量数组搬移 + 整段算术循环实现：
 * 旧的按样本 read()/writeSample() 双循环在每样本上重复 when 分发与方法调用，
 * 在 96kHz 音源 + 弱核 + ART 未完全 JIT 时会成为实时瓶颈，与 EQ 叠加后直接欠载
 * （电流声/卡顿/变速）。
 */
@UnstableApi
class OutputFormatProcessor(
    private val requestedBitDepth: String,
    private val preferFloatWhenAutomatic: Boolean,
) : BaseAudioProcessor() {
    private var source = AudioProcessor.AudioFormat.NOT_SET
    private var target = AudioProcessor.AudioFormat.NOT_SET

    /** 可复用的批量转换缓冲（只增不缩，一次性分配，热循环零分配） */
    private var inFloat = FloatArray(0)
    private var outFloat = FloatArray(0)
    private var inShort = ShortArray(0)
    private var outShort = ShortArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding !in SUPPORTED) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        source = inputAudioFormat
        target = AudioProcessor.AudioFormat(
            inputAudioFormat.sampleRate,
            inputAudioFormat.channelCount,
            targetEncoding(inputAudioFormat.encoding),
        )
        EqState.outSampleRate = target.sampleRate
        EqState.outChannelCount = target.channelCount
        EqState.outEncoding = target.encoding
        return if (source.sampleRate == target.sampleRate && source.encoding == target.encoding) {
            AudioProcessor.AudioFormat.NOT_SET
        } else target
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inFormat = source
        val outFormat = target
        if (inFormat == AudioProcessor.AudioFormat.NOT_SET || outFormat == AudioProcessor.AudioFormat.NOT_SET) return
        val inputBytes = bytesPerSample(inFormat.encoding)
        val outputBytes = bytesPerSample(outFormat.encoding)
        if (inputBytes == 0 || outputBytes == 0) return

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val frames = inputBuffer.remaining() / (inputBytes * inFormat.channelCount)
        if (frames == 0) { inputBuffer.position(inputBuffer.limit()); return }

        val out = replaceOutputBuffer(frames * outFormat.channelCount * outputBytes).order(ByteOrder.LITTLE_ENDIAN)
        val inEnc = inFormat.encoding
        val outEnc = outFormat.encoding

        when {
            // ── 快速路径：float → I16（Oboe 管线末端）──
            inEnc == C.ENCODING_PCM_FLOAT && outEnc == C.ENCODING_PCM_16BIT -> {
                val n = frames * inFormat.channelCount
                if (inFloat.size < n) { inFloat = FloatArray(n); outShort = ShortArray(n) }
                inputBuffer.asFloatBuffer().get(inFloat, 0, n)
                for (i in 0 until n) {
                    outShort[i] = (inFloat[i] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
                }
                out.asShortBuffer().put(outShort, 0, n)
                out.position(frames * outFormat.channelCount * outputBytes)
            }

            // ── 快速路径：I16 → float（Oboe 管线首端）──
            inEnc == C.ENCODING_PCM_16BIT && outEnc == C.ENCODING_PCM_FLOAT -> {
                val n = frames * inFormat.channelCount
                if (inShort.size < n) { inShort = ShortArray(n); outFloat = FloatArray(n) }
                inputBuffer.asShortBuffer().get(inShort, 0, n)
                for (i in 0 until n) {
                    outFloat[i] = inShort[i].toInt() / 32768f
                }
                out.asFloatBuffer().put(outFloat, 0, n)
                out.position(frames * outFormat.channelCount * outputBytes)
            }

            // float→float 或同格式：onConfigure 已返回 NOT_SET，不会进来
            else -> {
                // 24/32-bit 等少见格式：保持旧的按帧按声道循环（绝对偏移读法）。
                // 注意：这里 by puts 自然推进 out.position，不能在 limit 上手动定位
                //（输入/输出字节数可能不同，如 float→I24）。
                val inCh = inFormat.channelCount
                val start = inputBuffer.position()
                for (frame in 0 until frames) {
                    for (channel in 0 until inCh) {
                        val sample = read(inputBuffer, start, frame, channel, inFormat, inputBytes).coerceIn(-1f, 1f)
                        writeSample(out, sample, outEnc)
                    }
                }
                inputBuffer.position(inputBuffer.limit())
            }
        }
        // asFloatBuffer()/asShortBuffer() 只推进视图，不推进原 ByteBuffer；
        // 快速路径必须显式消费输入，否则同一块 PCM 会被 ExoPlayer 无限重试。
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onReset() {
        source = AudioProcessor.AudioFormat.NOT_SET
        target = AudioProcessor.AudioFormat.NOT_SET
    }

    private fun read(buffer: ByteBuffer, start: Int, frame: Int, channel: Int, format: AudioProcessor.AudioFormat, bytes: Int): Float {
        val offset = start + (frame * format.channelCount + channel) * bytes
        return when (format.encoding) {
            C.ENCODING_PCM_16BIT -> buffer.getShort(offset) / 32768f
            C.ENCODING_PCM_24BIT -> {
                var value = (buffer.get(offset).toInt() and 0xFF) or
                    ((buffer.get(offset + 1).toInt() and 0xFF) shl 8) or
                    ((buffer.get(offset + 2).toInt() and 0xFF) shl 16)
                if (value and 0x800000 != 0) value -= 1 shl 24
                value / 8388608f
            }
            C.ENCODING_PCM_32BIT -> buffer.getInt(offset) / 2147483648f
            C.ENCODING_PCM_FLOAT -> buffer.getFloat(offset)
            else -> 0f
        }
    }

    private fun writeSample(buffer: ByteBuffer, sample: Float, encoding: Int) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> buffer.putShort((sample * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            C.ENCODING_PCM_24BIT -> {
                val value = (sample * 8388607f).toInt().coerceIn(-8388608, 8388607)
                buffer.put((value and 0xFF).toByte())
                buffer.put(((value shr 8) and 0xFF).toByte())
                buffer.put(((value shr 16) and 0xFF).toByte())
            }
            C.ENCODING_PCM_32BIT -> buffer.putInt((sample * 2147483647.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())
            C.ENCODING_PCM_FLOAT -> buffer.putFloat(sample)
        }
    }

    private fun targetEncoding(inputEncoding: Int): Int = when (requestedBitDepth) {
        "16" -> C.ENCODING_PCM_16BIT
        "24" -> C.ENCODING_PCM_24BIT
        "32" -> C.ENCODING_PCM_32BIT
        "float" -> C.ENCODING_PCM_FLOAT
        "auto" -> if (preferFloatWhenAutomatic) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT
        else -> if (preferFloatWhenAutomatic) C.ENCODING_PCM_FLOAT else inputEncoding
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    private companion object {
        val SUPPORTED = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)
    }
}
