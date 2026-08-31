/*
 * Copyright 2022-2026 mkckr0
 * Licensed under the Apache License, Version 2.0.
 */

package io.github.mkckr0.audio_share_app.service

import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioProtocol {
    const val VERSION_V2 = 2
    const val MAGIC_V2 = 0x32534141
    const val HEADER_SIZE_V2 = 32
    const val FLAG_AUDIO = 1
    const val FLAG_REGISTRATION = 2
    const val FLAG_NACK = 4
    const val FLAG_RETRANSMITTED = 8

    data class Packet(
        val sequence: UInt,
        val frameIndex: Long,
        val frameCount: Int,
        val retransmitted: Boolean,
        val payload: ByteBuffer,
    )

    fun parseAudioPacket(datagram: ByteBuffer, expectedSessionId: Long): Packet? {
        val input = datagram.slice().order(ByteOrder.LITTLE_ENDIAN)
        if (input.remaining() < HEADER_SIZE_V2 || input.int != MAGIC_V2) return null
        val version = input.get().toInt() and 0xff
        val flags = input.get().toInt() and 0xff
        val headerSize = input.short.toInt() and 0xffff
        val sessionId = input.long
        val sequence = input.int.toUInt()
        val frameIndex = input.long
        val frameCount = input.short.toInt() and 0xffff
        val payloadSize = input.short.toInt() and 0xffff
        if (version != VERSION_V2 || headerSize != HEADER_SIZE_V2 || sessionId != expectedSessionId) return null
        if (flags and FLAG_AUDIO == 0 || payloadSize != input.remaining()) return null
        return Packet(
            sequence = sequence,
            frameIndex = frameIndex,
            frameCount = frameCount,
            retransmitted = flags and FLAG_RETRANSMITTED != 0,
            payload = input.slice().order(ByteOrder.LITTLE_ENDIAN),
        )
    }

    fun controlPacket(flags: Int, sessionId: Long, sequence: UInt = 0u): ByteBuffer {
        return ByteBuffer.allocate(HEADER_SIZE_V2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(MAGIC_V2)
            .put(VERSION_V2.toByte())
            .put(flags.toByte())
            .putShort(HEADER_SIZE_V2.toShort())
            .putLong(sessionId)
            .putInt(sequence.toInt())
            .putLong(0)
            .putShort(0)
            .putShort(0)
            .also { it.flip() }
    }
}

data class NetworkAudioPacket(
    val data: ByteBuffer,
    val sequence: UInt? = null,
    val frameIndex: Long? = null,
    val frameCount: Int = 0,
    val retransmitted: Boolean = false,
)
