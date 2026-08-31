/*
 * Copyright 2022-2026 mkckr0
 * Licensed under the Apache License, Version 2.0.
 */

package io.github.mkckr0.audio_share_app.service

import java.nio.ByteBuffer

data class BufferWriteResult(
    val completed: Boolean,
    val shortWrites: Long,
    val errorCode: Int? = null,
)

object AudioBufferWriter {
    const val ERROR_WRITE_STALLED = -10_000

    suspend fun writeFully(
        buffer: ByteBuffer,
        write: (ByteBuffer, Int) -> Int,
        onZeroWrite: suspend () -> Unit,
        maxConsecutiveZeroWrites: Int = 50,
    ): BufferWriteResult {
        var shortWrites = 0L
        var consecutiveZeroWrites = 0
        while (buffer.hasRemaining()) {
            val requested = buffer.remaining()
            val written = write(buffer, requested)
            when {
                written > requested -> return BufferWriteResult(false, shortWrites, ERROR_WRITE_STALLED)
                written > 0 -> {
                    consecutiveZeroWrites = 0
                    if (written < requested) shortWrites++
                }
                written == 0 -> {
                    shortWrites++
                    consecutiveZeroWrites++
                    if (consecutiveZeroWrites >= maxConsecutiveZeroWrites) {
                        return BufferWriteResult(false, shortWrites, ERROR_WRITE_STALLED)
                    }
                    onZeroWrite()
                }
                else -> return BufferWriteResult(false, shortWrites, written)
            }
        }
        return BufferWriteResult(true, shortWrites)
    }
}
