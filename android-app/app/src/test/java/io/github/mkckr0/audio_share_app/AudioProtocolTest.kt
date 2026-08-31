package io.github.mkckr0.audio_share_app

import io.github.mkckr0.audio_share_app.service.AudioProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioProtocolTest {
    @Test
    fun parsesValidV2AudioPacket() {
        val session = 42L
        val control = AudioProtocol.controlPacket(AudioProtocol.FLAG_AUDIO, session, UInt.MAX_VALUE)
        val packet = ByteBuffer.allocate(AudioProtocol.HEADER_SIZE_V2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        packet.put(control)
        packet.putShort(AudioProtocol.HEADER_SIZE_V2 - 4, 2)
        packet.putShort(AudioProtocol.HEADER_SIZE_V2 - 2, 4)
        packet.put(byteArrayOf(1, 2, 3, 4)).flip()
        val parsed = AudioProtocol.parseAudioPacket(packet, session)
        assertNotNull(parsed)
        assertEquals(UInt.MAX_VALUE, parsed!!.sequence)
        assertEquals(2, parsed.frameCount)
    }

    @Test
    fun rejectsWrongSession() {
        val packet = AudioProtocol.controlPacket(AudioProtocol.FLAG_AUDIO, 10)
        assertNull(AudioProtocol.parseAudioPacket(packet, 11))
    }
}
