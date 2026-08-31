package io.github.mkckr0.audio_share_app

import io.github.mkckr0.audio_share_app.service.AudioBufferWriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AudioBufferWriterTest {
    @Test
    fun consumesZeroPartialAndFullWritesExactlyOnce() = runBlocking {
        val input = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6))
        val output = mutableListOf<Byte>()
        val sizes = ArrayDeque(listOf(0, 2, 4))
        val result = AudioBufferWriter.writeFully(input, { data, requested ->
            val count = minOf(sizes.removeFirst(), requested)
            repeat(count) { output += data.get() }
            count
        }, {})
        assertTrue(result.completed)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), output)
        assertEquals(2, result.shortWrites)
    }

    @Test
    fun reportsAStalledWriterWithoutDiscardingInput() = runBlocking {
        val input = ByteBuffer.wrap(byteArrayOf(1, 2, 3))
        val result = AudioBufferWriter.writeFully(
            input,
            write = { _, _ -> 0 },
            onZeroWrite = {},
            maxConsecutiveZeroWrites = 3,
        )
        assertEquals(false, result.completed)
        assertEquals(AudioBufferWriter.ERROR_WRITE_STALLED, result.errorCode)
        assertEquals(0, input.position())
    }
}
