/*
 * Copyright 2022-2026 mkckr0
 * Licensed under the Apache License, Version 2.0.
 */

package io.github.mkckr0.audio_share_app.service

import io.github.mkckr0.audio_share_app.pb.Client
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

data class MixerSource(val data: ByteBuffer, val volume: Float)

object PcmMixer {
    fun bytesPerSample(encoding: Client.AudioFormat.Encoding): Int = when (encoding) {
        Client.AudioFormat.Encoding.ENCODING_PCM_FLOAT -> 4
        Client.AudioFormat.Encoding.ENCODING_PCM_8BIT -> 1
        Client.AudioFormat.Encoding.ENCODING_PCM_16BIT -> 2
        Client.AudioFormat.Encoding.ENCODING_PCM_24BIT -> 3
        Client.AudioFormat.Encoding.ENCODING_PCM_32BIT -> 4
        else -> 0
    }

    fun frameSize(format: Client.AudioFormat): Int = bytesPerSample(format.encoding) * format.channels

    fun isMixableFormat(a: Client.AudioFormat, b: Client.AudioFormat): Boolean =
        a.encoding == b.encoding && a.channels == b.channels && a.sampleRate == b.sampleRate

    fun mix(format: Client.AudioFormat, sources: List<MixerSource>, bytes: Int): ByteBuffer {
        val sampleBytes = bytesPerSample(format.encoding)
        val alignedBytes = if (sampleBytes == 0) 0 else bytes - bytes % sampleBytes
        val output = ByteBuffer.allocate(alignedBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (sources.isEmpty() || alignedBytes == 0) return output.flipBuffer()

        // Static headroom preserves a single unity-gain source exactly while
        // preventing deterministic clipping when several full-scale sources mix.
        val headroom = 1.0 / max(1.0, sources.sumOf { abs(it.volume.toDouble()) })
        var offset = 0
        while (offset < alignedBytes) {
            var mixed = 0.0
            for (source in sources) {
                mixed += readNormalized(format.encoding, source.data, source.data.position() + offset) * source.volume
            }
            writeNormalized(format.encoding, output, (mixed * headroom).coerceIn(-1.0, 1.0))
            offset += sampleBytes
        }
        return output.flipBuffer()
    }

    fun silence(format: Client.AudioFormat, frames: Int): ByteBuffer {
        val bytes = frames * frameSize(format)
        val result = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (format.encoding == Client.AudioFormat.Encoding.ENCODING_PCM_8BIT) {
            repeat(bytes) { result.put(128.toByte()) }
        } else {
            result.position(bytes)
        }
        return result.flipBuffer()
    }

    fun conceal(format: Client.AudioFormat, frames: Int, lastFrame: ByteBuffer?): ByteBuffer {
        if (lastFrame == null || lastFrame.remaining() < frameSize(format)) return silence(format, frames)
        val output = ByteBuffer.allocate(frames * frameSize(format)).order(ByteOrder.LITTLE_ENDIAN)
        val fadeFrames = minOf(frames, max(1, format.sampleRate / 200)) // 5 ms
        val sampleBytes = bytesPerSample(format.encoding)
        repeat(frames) { frame ->
            val gain = if (frame < fadeFrames) 1.0 - (frame + 1.0) / fadeFrames else 0.0
            repeat(format.channels) { channel ->
                val sample = readNormalized(format.encoding, lastFrame, lastFrame.position() + channel * sampleBytes)
                writeNormalized(format.encoding, output, sample * gain)
            }
        }
        return output.flipBuffer()
    }

    fun fadeIn(format: Client.AudioFormat, input: ByteBuffer): ByteBuffer {
        val source = input.slice().order(ByteOrder.LITTLE_ENDIAN)
        val totalFrames = source.remaining() / frameSize(format)
        val fadeFrames = minOf(totalFrames, max(1, format.sampleRate / 200))
        val output = ByteBuffer.allocate(source.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        val sampleBytes = bytesPerSample(format.encoding)
        repeat(totalFrames) { frame ->
            val gain = if (frame < fadeFrames) (frame + 1.0) / fadeFrames else 1.0
            repeat(format.channels) {
                writeNormalized(format.encoding, output, readNormalized(format.encoding, source, source.position()) * gain)
                source.position(source.position() + sampleBytes)
            }
        }
        return output.flipBuffer()
    }

    fun resampleFrames(format: Client.AudioFormat, input: ByteBuffer, outputFrames: Int): ByteBuffer {
        val source = input.slice().order(ByteOrder.LITTLE_ENDIAN)
        val sourceFrames = source.remaining() / frameSize(format)
        if (sourceFrames == outputFrames) return source
        if (sourceFrames <= 0 || outputFrames <= 0) return silence(format, outputFrames.coerceAtLeast(0))
        val sampleBytes = bytesPerSample(format.encoding)
        val output = ByteBuffer.allocate(outputFrames * frameSize(format)).order(ByteOrder.LITTLE_ENDIAN)
        repeat(outputFrames) { outputFrame ->
            val position = if (outputFrames == 1) 0.0 else
                outputFrame.toDouble() * (sourceFrames - 1) / (outputFrames - 1)
            val firstFrame = position.toInt().coerceAtMost(sourceFrames - 1)
            val secondFrame = (firstFrame + 1).coerceAtMost(sourceFrames - 1)
            val fraction = position - firstFrame
            repeat(format.channels) { channel ->
                val firstOffset = (firstFrame * format.channels + channel) * sampleBytes
                val secondOffset = (secondFrame * format.channels + channel) * sampleBytes
                val first = readNormalized(format.encoding, source, firstOffset)
                val second = readNormalized(format.encoding, source, secondOffset)
                writeNormalized(format.encoding, output, first + (second - first) * fraction)
            }
        }
        return output.flipBuffer()
    }

    private fun readNormalized(encoding: Client.AudioFormat.Encoding, buffer: ByteBuffer, offset: Int): Double = when (encoding) {
        Client.AudioFormat.Encoding.ENCODING_PCM_FLOAT -> buffer.order(ByteOrder.LITTLE_ENDIAN).getFloat(offset).toDouble()
        Client.AudioFormat.Encoding.ENCODING_PCM_8BIT -> ((buffer.get(offset).toInt() and 0xff) - 128) / 128.0
        Client.AudioFormat.Encoding.ENCODING_PCM_16BIT -> buffer.order(ByteOrder.LITTLE_ENDIAN).getShort(offset) / 32768.0
        Client.AudioFormat.Encoding.ENCODING_PCM_24BIT -> {
            val raw = (buffer.get(offset).toInt() and 0xff) or
                ((buffer.get(offset + 1).toInt() and 0xff) shl 8) or
                (buffer.get(offset + 2).toInt() shl 16)
            raw / 8388608.0
        }
        Client.AudioFormat.Encoding.ENCODING_PCM_32BIT -> buffer.order(ByteOrder.LITTLE_ENDIAN).getInt(offset) / 2147483648.0
        else -> 0.0
    }

    private fun writeNormalized(encoding: Client.AudioFormat.Encoding, buffer: ByteBuffer, value: Double) {
        when (encoding) {
            Client.AudioFormat.Encoding.ENCODING_PCM_FLOAT -> buffer.putFloat(value.toFloat())
            Client.AudioFormat.Encoding.ENCODING_PCM_8BIT -> buffer.put((value * 128.0 + 128.0).toInt().coerceIn(0, 255).toByte())
            Client.AudioFormat.Encoding.ENCODING_PCM_16BIT -> buffer.putShort((value * 32768.0).toInt().coerceIn(-32768, 32767).toShort())
            Client.AudioFormat.Encoding.ENCODING_PCM_24BIT -> {
                val sample = (value * 8388608.0).toInt().coerceIn(-8388608, 8388607)
                buffer.put((sample and 0xff).toByte())
                buffer.put((sample shr 8 and 0xff).toByte())
                buffer.put((sample shr 16 and 0xff).toByte())
            }
            Client.AudioFormat.Encoding.ENCODING_PCM_32BIT -> {
                val sample = (value * 2147483648.0).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                buffer.putInt(sample.toInt())
            }
            else -> Unit
        }
    }

    private fun ByteBuffer.flipBuffer(): ByteBuffer = apply { flip() }
}
