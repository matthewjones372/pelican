package io.github.matthewjones372.pelican.spi

import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.BodySink

/**
 * A JSON payload as one array, through [BodyCodec.encodeTo] so a streaming codec writes no String (spec 0090), or
 * the bytes the codec already holds. One place, so every backend answers the same bytes.
 */
fun <T> BodyCodec<T>.encodedBytes(value: T): ByteArray =
    encoded(value) ?: Gathered().also { encodeTo(value, it) }.bytes()

/** The bytes a codec wrote, as one array. */
private class Gathered : BodySink {
    private var buffer = EMPTY
    private var size = 0

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (size + length > buffer.size) buffer = buffer.copyOf(grown(size + length))
        System.arraycopy(bytes, offset, buffer, size, length)
        size += length
    }

    fun bytes(): ByteArray = if (size == buffer.size) buffer else buffer.copyOf(size)

    /** Exactly [needed] while a body is small, so a body a codec writes in a piece or two is copied least. */
    private fun grown(needed: Int): Int = if (needed <= EXACT_UP_TO) needed else maxOf(needed, buffer.size * 2)
}

private const val EXACT_UP_TO = 32 * 1024

private val EMPTY = ByteArray(0)
