package io.github.matthewjones372.pelican.spi

import io.github.matthewjones372.pelican.BodyDecodeFailure
import io.github.matthewjones372.pelican.PayloadTooLarge
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * A newline-delimited request body, split into frames and decoded one at a time
 * as the bytes arrive.
 *
 * Fed chunks rather than reading a stream of its own: a request body is a
 * `Source` on one backend, an `InputStream` on another and a `ByteReadChannel`
 * on the third, and handing over the next bytes is the one thing all three can
 * do. It is the mirror of `NdjsonOutput.frame`, which is what makes an upload
 * and a download agree on where a frame ends.
 *
 * One of these belongs to one request. It holds the frame a chunk stopped in
 * the middle of, so it is neither shareable nor reusable.
 */
class NdjsonFrames(
    private val codecs: RequestBodyCodecs,
    private val contentType: String?,
    /** The most one frame may weigh. See `Api.maxFrameBytes`. */
    private val maxFrameBytes: Long,
) {
    /** The frame the last chunk stopped in the middle of. */
    private val partial = ByteArrayOutputStream()

    /**
     * How many lines have been read, which is the frame number a refusal names.
     * Counted over every line the body carries, blank ones included, so it is
     * the line of the upload a caller can go and look at.
     */
    private var line = 0

    /** The values these bytes complete, in order. */
    fun push(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): List<Any?> = buildList {
        var from = offset
        val end = offset + length
        while (from < end) {
            val newline = indexOfNewline(bytes, from, end)
            if (newline < 0) {
                hold(bytes, from, end - from)
                return@buildList
            }
            hold(bytes, from, newline - from)
            addAll(finish())
            from = newline + 1
        }
    }

    /**
     * Whatever the last chunk left behind. Not every producer writes a newline
     * after its last document, so the tail is a frame rather than a remainder.
     */
    fun end(): List<Any?> = finish()

    /**
     * The same, over a blocking stream: a chunk is read when the sequence is
     * asked for a frame it does not already hold, so a handler that consumes
     * slowly reads slowly. For the backends whose request body is an
     * `InputStream`; the ones with a stream type of their own feed [push] and
     * [end] from it directly.
     */
    fun readFrom(input: InputStream): Sequence<Any?> = sequence {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            yieldAll(push(buffer, 0, read))
        }
        yieldAll(end())
    }

    /**
     * Refused on the byte that crosses the line rather than once the frame has
     * been assembled, since holding it whole is the thing the limit exists to
     * prevent.
     */
    private fun hold(bytes: ByteArray, from: Int, length: Int) {
        if (partial.size() + length > maxFrameBytes) {
            throw PayloadTooLarge(
                maxFrameBytes,
                "Frame ${line + 1} of the request body is longer than the $maxFrameBytes bytes one frame " +
                    "may carry. A stream has no total length to bound, so this is the bound there is: " +
                    "raise maxFrameBytes in api { }, or send shorter documents.",
            )
        }
        partial.write(bytes, from, length)
    }

    /** The frame just completed, decoded, or nothing where the line carried none. */
    private fun finish(): List<Any?> {
        line++
        val text = String(partial.toByteArray(), StandardCharsets.UTF_8)
        partial.reset()

        // A blank line is not a document. Every NDJSON writer ends its last one
        // with a newline, so refusing the empty tail that leaves would refuse
        // well-formed bodies.
        if (text.isBlank()) return emptyList()

        return try {
            listOf(codecs.decode(contentType, text))
        } catch (t: BodyDecodeFailure) {
            // Which frame, because the caller is holding a file and a body that
            // "could not be decoded" says nothing about which line to look at.
            throw BodyDecodeFailure("Frame $line of the request body could not be read: ${t.message}", t)
        }
    }
}

private const val NEWLINE: Byte = '\n'.code.toByte()

/** As much of the socket as is read at once, the same four kilobytes a strict body reads. */
private const val READ_BUFFER_BYTES = 4096

/**
 * Where the next line ends, or -1. Byte-wise rather than over decoded text:
 * UTF-8 never puts an ASCII byte inside a multi-byte sequence, so a newline is
 * a newline whatever the frame around it says — and the frame's own bytes are
 * what the size limit has to count.
 */
private fun indexOfNewline(bytes: ByteArray, from: Int, end: Int): Int {
    for (at in from until end) if (bytes[at] == NEWLINE) return at
    return -1
}
