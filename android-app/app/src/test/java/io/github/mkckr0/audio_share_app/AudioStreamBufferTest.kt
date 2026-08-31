package io.github.mkckr0.audio_share_app

import io.github.mkckr0.audio_share_app.pb.Client
import io.github.mkckr0.audio_share_app.service.AudioStreamBuffer
import io.github.mkckr0.audio_share_app.service.NetworkAudioPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class AudioStreamBufferTest {
    private val format = Client.AudioFormat.newBuilder()
        .setEncoding(Client.AudioFormat.Encoding.ENCODING_PCM_8BIT)
        .setChannels(1).setSampleRate(1000).build()

    @Test
    fun reordersPacketsAndRequestsMissingSequence() {
        val nacks = mutableListOf<UInt>()
        val buffer = AudioStreamBuffer(format, targetLatencyMs = 20, requestRetransmit = nacks::add)
        buffer.offer(packet(0u, 0, 10, 130))
        buffer.offer(packet(2u, 20, 10, 132))
        assertEquals(listOf(1u), nacks)
        buffer.offer(packet(1u, 10, 10, 131, retransmitted = true))
        val output = buffer.readFrames(30)!!
        val bytes = ByteArray(output.remaining()).also(output::get)
        assertArrayEquals(ByteArray(10) { 130.toByte() } + ByteArray(10) { 131.toByte() } + ByteArray(10) { 132.toByte() }, bytes)
        assertEquals(1, buffer.stats.retransmitted)
    }

    @Test
    fun nackSkipsPacketsAlreadyHeldForReordering() {
        val nacks = mutableListOf<UInt>()
        val buffer = AudioStreamBuffer(format, targetLatencyMs = 20, requestRetransmit = nacks::add)
        buffer.offer(packet(0u, 0, 10, 130))
        buffer.offer(packet(2u, 20, 10, 132))
        buffer.offer(packet(3u, 30, 10, 133))
        assertEquals(listOf(1u), nacks)
    }

    @Test
    fun concealsExpiredGapWithoutChangingFrameCount() {
        val buffer = AudioStreamBuffer(format, targetLatencyMs = 10, requestRetransmit = {})
        buffer.offer(packet(0u, 0, 10, 130))
        buffer.offer(packet(2u, 20, 10, 132))
        val output = buffer.readFrames(20)!!
        assertEquals(20, output.remaining())
        assertEquals(1, buffer.stats.lost)
        assertEquals(10, buffer.stats.concealedFrames)
    }

    @Test
    fun sequenceWrapRemainsContiguous() {
        val buffer = AudioStreamBuffer(format, targetLatencyMs = 20, requestRetransmit = {})
        buffer.offer(packet(UInt.MAX_VALUE, 0, 10, 130))
        buffer.offer(packet(0u, 10, 10, 131))
        val output = buffer.readFrames(20)!!
        assertEquals(20, output.remaining())
        assertEquals(0, buffer.stats.lost)
        assertEquals(0, buffer.stats.reordered)
    }

    @Test
    fun deterministicLossAndReorderPreserveTimelineLength() {
        val nacks = mutableListOf<UInt>()
        val buffer = AudioStreamBuffer(
            format,
            targetLatencyMs = 10,
            maxLatencyMs = 2000,
            requestRetransmit = nacks::add,
        )
        val packets = (0 until 100)
            .filter { it != 37 }
            .map { packet(it.toUInt(), it * 10L, 10, 128 + it % 10) }
            .toMutableList()
        for (index in 10 until packets.lastIndex step 10) {
            val temporary = packets[index]
            packets[index] = packets[index + 1]
            packets[index + 1] = temporary
        }
        packets.forEach(buffer::offer)

        val output = buffer.readFrames(1000)!!
        assertEquals(1000, output.remaining())
        assertEquals(1, buffer.stats.lost)
        assertEquals(10, buffer.stats.concealedFrames)
        assertEquals(true, 37u in nacks)
    }

    @Test
    fun rejectsMisalignedV2Payload() {
        val buffer = AudioStreamBuffer(format, targetLatencyMs = 1, requestRetransmit = {})
        buffer.offer(NetworkAudioPacket(ByteBuffer.wrap(byteArrayOf(1, 2)), 0u, 0, 1))
        assertEquals(1, buffer.stats.invalidPackets)
    }

    @Test
    fun underflowRebuffersBeforeConsumingNewAudio() {
        val buffer = AudioStreamBuffer(format, targetLatencyMs = 20, requestRetransmit = {})
        buffer.offer(packet(0u, 0, 20, 130))
        assertEquals(20, buffer.readFrames(20)!!.remaining())

        assertEquals(10, buffer.readFrames(10)!!.remaining())
        buffer.offer(packet(1u, 20, 10, 131))
        assertEquals(10, buffer.readFrames(10)!!.remaining())
        assertEquals(10, buffer.queuedMilliseconds())

        buffer.offer(packet(2u, 30, 10, 132))
        assertEquals(10, buffer.readFrames(10)!!.remaining())
        assertEquals(10, buffer.queuedMilliseconds())
        assertEquals(1, buffer.stats.underflowEvents)
        assertEquals(1, buffer.stats.rebufferEvents)
        assertEquals(10, buffer.stats.rebufferedFrames)
    }

    private fun packet(sequence: UInt, frame: Long, frames: Int, value: Int, retransmitted: Boolean = false) =
        NetworkAudioPacket(ByteBuffer.wrap(ByteArray(frames) { value.toByte() }), sequence, frame, frames, retransmitted)
}
