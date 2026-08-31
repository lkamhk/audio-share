/*
 * Copyright 2022-2026 mkckr0
 * Licensed under the Apache License, Version 2.0.
 */

package io.github.mkckr0.audio_share_app.service

import io.github.mkckr0.audio_share_app.pb.Client
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import kotlin.math.max

data class StreamStats(
    var received: Long = 0,
    var lost: Long = 0,
    var late: Long = 0,
    var reordered: Long = 0,
    var retransmitted: Long = 0,
    var concealedFrames: Long = 0,
    var droppedBytes: Long = 0,
    var driftCorrections: Long = 0,
    var invalidPackets: Long = 0,
    var underflowEvents: Long = 0,
    var rebufferEvents: Long = 0,
    var rebufferedFrames: Long = 0,
)

class AudioStreamBuffer(
    private val format: Client.AudioFormat,
    private val targetLatencyMs: Int,
    private val maxLatencyMs: Int = 120,
    private val requestRetransmit: (UInt) -> Unit,
) {
    private val frameSize = PcmMixer.frameSize(format)
    private val targetFrames = format.sampleRate.toLong() * targetLatencyMs / 1000
    private val effectiveMaxLatencyMs = max(maxLatencyMs, targetLatencyMs + 50)
    private val maxBytes = (format.sampleRate.toLong() * effectiveMaxLatencyMs / 1000 * frameSize).toInt()
    private val byteQueue = ArrayDeque<ByteBuffer>()
    private val packets = HashMap<UInt, NetworkAudioPacket>()
    private val requested = HashSet<UInt>()
    private var queuedBytes = 0
    private var expectedSequence: UInt? = null
    private var expectedFrameIndex: Long? = null
    private var started = false
    private var rebuffering = false
    private var fadeNextOutput = false
    private var lastOutputFrame: ByteBuffer? = null
    private var fadeNextPacket = false
    val stats = StreamStats()

    @Synchronized
    fun offer(packet: NetworkAudioPacket) {
        stats.received++
        val sequence = packet.sequence
        val frameIndex = packet.frameIndex
        if (sequence == null || frameIndex == null) {
            val alignedBytes = packet.data.remaining() - packet.data.remaining() % frameSize
            if (alignedBytes != packet.data.remaining()) stats.invalidPackets++
            if (alignedBytes <= 0) return
            val aligned = packet.data.slice().order(ByteOrder.LITTLE_ENDIAN)
            aligned.limit(alignedBytes)
            enqueue(aligned)
            trimToLimit()
            return
        }
        if (packet.frameCount <= 0 || packet.data.remaining() != packet.frameCount * frameSize) {
            stats.invalidPackets++
            return
        }
        if (packet.retransmitted) stats.retransmitted++
        val renderedFrame = expectedFrameIndex
        if (renderedFrame != null && frameIndex + packet.frameCount <= renderedFrame) {
            stats.late++
            return
        }
        if (packets.putIfAbsent(sequence, packet) != null) return
        if (expectedSequence == null) {
            expectedSequence = sequence
            expectedFrameIndex = frameIndex
        } else {
            val delta = sequence - expectedSequence!!
            if (delta > 0u && delta < UInt.MAX_VALUE / 2u) {
                stats.reordered++
                var missing = expectedSequence!!
                repeat(minOf(delta.toInt(), 8)) {
                    if (!packets.containsKey(missing) && requested.add(missing)) {
                        requestRetransmit(missing)
                    }
                    missing++
                }
            }
        }
        drainContiguous()
        trimToLimit()
        if (queuedFrames() * frameSize > maxBytes && packets.isNotEmpty()) {
            concealKnownGap()
            trimToLimit()
        }
    }

    @Synchronized
    fun isReady(): Boolean {
        drainContiguous()
        if (!started && contiguousFrames() < targetFrames && queuedFrames() >= targetFrames && packets.isNotEmpty()) {
            concealKnownGap()
        }
        if (!started && contiguousFrames() >= targetFrames) started = true
        return started
    }

    @Synchronized
    fun readFrames(frames: Int): ByteBuffer? {
        if (!isReady()) return null
        drainContiguous()
        if (rebuffering) {
            if (contiguousFrames() < targetFrames && queuedFrames() >= targetFrames && packets.isNotEmpty()) {
                concealKnownGap()
            }
            if (contiguousFrames() < targetFrames) {
                stats.rebufferedFrames += frames
                stats.concealedFrames += frames
                return PcmMixer.silence(format, frames)
            }
            rebuffering = false
            fadeNextOutput = true
        }
        var sourceFrames = frames
        val queuedMs = queuedBytes / frameSize * 1000 / format.sampleRate
        if (queuedMs > targetLatencyMs + 5 && queuedBytes >= (frames + 1) * frameSize) {
            sourceFrames++
            stats.driftCorrections++
        } else if (queuedMs < targetLatencyMs - 5 && queuedBytes >= frames * frameSize) {
            sourceFrames--
            stats.driftCorrections++
        }
        val bytes = sourceFrames * frameSize
        if (queuedBytes < bytes && packets.isNotEmpty()) concealKnownGap()
        if (queuedBytes < bytes) {
            val missingFrames = (bytes - queuedBytes) / frameSize
            enqueue(PcmMixer.conceal(format, missingFrames, lastOutputFrame))
            stats.concealedFrames += missingFrames
            stats.underflowEvents++
            stats.rebufferEvents++
            rebuffering = true
        }
        val source = popBytes(bytes)
        val output = if (sourceFrames == frames) source else PcmMixer.resampleFrames(format, source, frames)
        if (!fadeNextOutput) return output
        fadeNextOutput = false
        return PcmMixer.fadeIn(format, output)
    }

    @Synchronized
    fun queuedMilliseconds(): Int = (queuedFrames() * 1000 / format.sampleRate).toInt()

    @Synchronized
    fun droppedMilliseconds(): Long = stats.droppedBytes / frameSize * 1000L / format.sampleRate

    private fun drainContiguous() {
        var expected = expectedSequence ?: return
        while (true) {
            val packet = packets.remove(expected) ?: break
            requested.remove(expected)
            val expectedFrame = expectedFrameIndex ?: packet.frameIndex!!
            val packetFrame = packet.frameIndex!!
            var payload = packet.data.slice().order(ByteOrder.LITTLE_ENDIAN)
            if (packetFrame < expectedFrame) {
                val trimFrames = minOf(expectedFrame - packetFrame, packet.frameCount.toLong()).toInt()
                payload.position(trimFrames * frameSize)
                payload = payload.slice().order(ByteOrder.LITTLE_ENDIAN)
            } else if (packetFrame > expectedFrame) {
                enqueue(PcmMixer.silence(format, (packetFrame - expectedFrame).toInt()))
                stats.concealedFrames += packetFrame - expectedFrame
            }
            if (fadeNextPacket) {
                payload = PcmMixer.fadeIn(format, payload)
                fadeNextPacket = false
            }
            enqueue(payload)
            expectedFrameIndex = packetFrame + packet.frameCount
            expected++
            expectedSequence = expected
        }
    }

    private fun concealKnownGap() {
        val expected = expectedSequence ?: return
        val expectedFrame = expectedFrameIndex ?: return
        val next = packets.entries.minByOrNull { (it.key - expected).toLong() } ?: return
        val sequenceGap = next.key - expected
        if (sequenceGap == 0u || sequenceGap >= UInt.MAX_VALUE / 2u) return
        val gapFrames = (next.value.frameIndex!! - expectedFrame).coerceAtLeast(0).toInt()
        if (gapFrames > 0) {
            enqueue(PcmMixer.conceal(format, gapFrames, lastOutputFrame))
            stats.concealedFrames += gapFrames
            fadeNextPacket = true
        }
        stats.lost += sequenceGap.toLong()
        requested.removeAll { sequence ->
            val delta = sequence - expected
            delta < sequenceGap
        }
        expectedSequence = next.key
        expectedFrameIndex = next.value.frameIndex
        drainContiguous()
    }

    private fun enqueue(buffer: ByteBuffer) {
        if (!buffer.hasRemaining()) return
        val copy = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        byteQueue.addLast(copy)
        queuedBytes += copy.remaining()
    }

    private fun popBytes(bytes: Int): ByteBuffer {
        val output = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (output.hasRemaining()) {
            val first = byteQueue.peekFirst() ?: break
            val count = minOf(first.remaining(), output.remaining())
            val oldLimit = first.limit()
            first.limit(first.position() + count)
            output.put(first)
            first.limit(oldLimit)
            queuedBytes -= count
            if (!first.hasRemaining()) byteQueue.removeFirst()
        }
        output.flip()
        if (output.remaining() >= frameSize) {
            val last = output.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            last.position(last.limit() - frameSize)
            lastOutputFrame = last.slice().order(ByteOrder.LITTLE_ENDIAN)
        }
        return output
    }

    private fun trimToLimit() {
        var trimmed = false
        while (queuedBytes > maxBytes && byteQueue.isNotEmpty()) {
            val dropped = byteQueue.removeFirst().remaining()
            queuedBytes -= dropped
            stats.droppedBytes += dropped
            trimmed = true
        }
        if (trimmed) fadeNextOutput = true
    }

    private fun queuedFrames(): Long = (queuedBytes + packets.values.sumOf { it.data.remaining() }).toLong() / frameSize

    private fun contiguousFrames(): Long = queuedBytes.toLong() / frameSize
}
