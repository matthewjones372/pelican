package dev.pelican.http4k

import dev.pelican.BodyCodec
import java.io.InputStream

/**
 * A stream over frames that are produced as they are asked for.
 *
 * This is what makes a streaming endpoint stream on a server-as-a-function.
 * The server writes the response by copying this stream to the socket, and a
 * copy loop reads before it writes — so one `read` pulls exactly one element
 * from the handler's sequence, encodes it, and hands those bytes over. Nothing
 * downstream ever sees the second element until the first has been written.
 *
 * [read] deliberately returns one frame at a time even when the caller offered
 * a larger buffer. Filling the buffer would mean pulling elements the caller
 * has not asked for yet, which is precisely the buffering a streamed endpoint
 * exists to avoid.
 *
 * How promptly those bytes then leave the machine is the server backend's
 * business: a backend that aggregates small writes will hold a frame until its
 * own buffer fills. That is the one part of streaming this module cannot
 * decide — see the note in `Server.kt`.
 */
internal class FrameInputStream(frames: Sequence<String>) : InputStream() {
    private val iterator = frames.iterator()
    private var current: ByteArray = ByteArray(0)
    private var position = 0

    /** True when there are bytes to hand over; false at the end of the stream. */
    private fun advance(): Boolean {
        while (position >= current.size) {
            if (!iterator.hasNext()) return false
            current = iterator.next().toByteArray()
            position = 0
        }
        return true
    }

    override fun read(): Int =
        if (!advance()) -1 else current[position++].toInt() and BYTE_MASK

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!advance()) return -1
        val n = minOf(len, current.size - position)
        System.arraycopy(current, position, b, off, n)
        position += n
        return n
    }

    override fun available(): Int = current.size - position
}

/**
 * Frames a stream of documents as one JSON array.
 *
 * Unlike NDJSON and SSE, core does not frame this one — the separators are the
 * backend's business, because Pekko already has `EntityStreamingSupport.json()`
 * and reimplementing it there would be worse. http4k has no equivalent, so the
 * commas are put in here: the opening bracket travels with the first element
 * rather than ahead of it, so an empty stream still renders `[]` and a failure
 * to produce the first element has not yet committed to an array.
 */
internal fun jsonArrayFrames(
    elements: Sequence<Any?>,
    codec: BodyCodec<Any?>,
): Sequence<String> = sequence {
    var seen = false
    for (element in elements) {
        yield((if (seen) "," else "[") + codec.encodeToString(element))
        seen = true
    }
    yield(if (seen) "]" else "[]")
}

/** A Kotlin Byte is signed; `InputStream.read` promises 0..255. */
private const val BYTE_MASK = 0xFF
