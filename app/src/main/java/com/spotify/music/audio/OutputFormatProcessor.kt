package com.spotify.music.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Converts PCM encoding; sample-rate conversion is delegated to Media3 SonicAudioProcessor. */
@UnstableApi
class OutputFormatProcessor(
    private val requestedBitDepth: String,
    private val preferFloatWhenAutomatic: Boolean,
) : BaseAudioProcessor() {
    private var source = AudioProcessor.AudioFormat.NOT_SET
    private var target = AudioProcessor.AudioFormat.NOT_SET

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
        val inputBytes = bytesPerSample(inFormat.encoding)
        val outputBytes = bytesPerSample(outFormat.encoding)
        if (inFormat == AudioProcessor.AudioFormat.NOT_SET || outFormat == AudioProcessor.AudioFormat.NOT_SET || inputBytes == 0) return
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val frames = inputBuffer.remaining() / (inputBytes * inFormat.channelCount)
        if (frames == 0) { inputBuffer.position(inputBuffer.limit()); return }
        val start = inputBuffer.position()
        val out = replaceOutputBuffer(frames * outFormat.channelCount * outputBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frames) {
            for (channel in 0 until inFormat.channelCount) {
                val sample = read(inputBuffer, start, frame, channel, inFormat, inputBytes).coerceIn(-1f, 1f)
                writeSample(out, sample, outFormat.encoding)
            }
        }
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
                var value = (buffer.get(offset).toInt() and 0xff) or
                    ((buffer.get(offset + 1).toInt() and 0xff) shl 8) or
                    ((buffer.get(offset + 2).toInt() and 0xff) shl 16)
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
                buffer.put((value and 0xff).toByte())
                buffer.put(((value shr 8) and 0xff).toByte())
                buffer.put(((value shr 16) and 0xff).toByte())
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
