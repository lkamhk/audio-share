package io.github.mkckr0.audio_share_app

import io.github.mkckr0.audio_share_app.pb.Client
import io.github.mkckr0.audio_share_app.service.MixerSource
import io.github.mkckr0.audio_share_app.service.PcmMixer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmMixerTest {

    @Test
    fun mix_pcm16_appliesVolumeWithHeadroom() {
        val format = Client.AudioFormat.newBuilder()
            .setEncoding(Client.AudioFormat.Encoding.ENCODING_PCM_16BIT)
            .setChannels(1)
            .setSampleRate(48000)
            .build()
        val source1 = shortBuffer(20_000.toShort())
        val source2 = shortBuffer(20_000.toShort())

        val mixed = PcmMixer.mix(
            format,
            listOf(
                MixerSource(source1, 1f),
                MixerSource(source2, 1f),
            ),
            2,
        ).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(20_000.toShort(), mixed.short)
    }

    @Test
    fun mix_pcm8_treatsSamplesAsUnsigned() {
        val format = Client.AudioFormat.newBuilder()
            .setEncoding(Client.AudioFormat.Encoding.ENCODING_PCM_8BIT)
            .setChannels(1).setSampleRate(48000).build()
        val input = ByteBuffer.wrap(byteArrayOf(128.toByte(), 255.toByte(), 0))
        val mixed = PcmMixer.mix(format, listOf(MixerSource(input, 1f)), 3)
        assertEquals(128, mixed.get().toInt() and 0xff)
        assertEquals(255, mixed.get().toInt() and 0xff)
        assertEquals(0, mixed.get().toInt() and 0xff)
    }

    @Test
    fun silence_pcm8_uses128AsZero() {
        val format = Client.AudioFormat.newBuilder()
            .setEncoding(Client.AudioFormat.Encoding.ENCODING_PCM_8BIT)
            .setChannels(2).setSampleRate(48000).build()
        val silence = PcmMixer.silence(format, 2)
        while (silence.hasRemaining()) assertEquals(128, silence.get().toInt() and 0xff)
    }

    @Test
    fun formatCompare_requiresExactMatch() {
        val format = Client.AudioFormat.newBuilder()
            .setEncoding(Client.AudioFormat.Encoding.ENCODING_PCM_16BIT)
            .setChannels(2)
            .setSampleRate(48000)
            .build()

        assertTrue(PcmMixer.isMixableFormat(format, format))
        assertFalse(PcmMixer.isMixableFormat(format, format.toBuilder().setSampleRate(44100).build()))
    }

    @Test
    fun unityGainIsSampleExactForWidePcmFormats() {
        val cases = listOf(
            Client.AudioFormat.Encoding.ENCODING_PCM_24BIT to byteArrayOf(0x56, 0x34, 0x12),
            Client.AudioFormat.Encoding.ENCODING_PCM_32BIT to byteArrayOf(0x78, 0x56, 0x34, 0x12),
            Client.AudioFormat.Encoding.ENCODING_PCM_FLOAT to ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN).putFloat(0.375f).array(),
        )
        cases.forEach { (encoding, bytes) ->
            val format = Client.AudioFormat.newBuilder()
                .setEncoding(encoding).setChannels(1).setSampleRate(48000).build()
            val output = PcmMixer.mix(format, listOf(MixerSource(ByteBuffer.wrap(bytes), 1f)), bytes.size)
            val actual = ByteArray(output.remaining()).also(output::get)
            org.junit.Assert.assertArrayEquals(encoding.name, bytes, actual)
        }
    }

    private fun shortBuffer(value: Short): ByteBuffer {
        return ByteBuffer.allocate(2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(value)
            .also { it.flip() }
    }
}
