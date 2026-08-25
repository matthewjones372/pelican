package io.github.matthewjones372.pelican.http4k

import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.SseOutput
import java.io.InputStream
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * A stream over frames produced as they are asked for, which is what makes a
 * streaming endpoint stream on a server-as-a-function: the copy loop reads
 * before it writes, so one `read` pulls exactly one element.
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

    /**
     * The server closes the body once written, or once writing fails — see
     * `respondWith`. That is the only signal a keep-alive's producer thread
     * gets that the client has gone.
     */
    override fun close() {
        (iterator as? AutoCloseable)?.close()
    }
}

/**
 * Injects an SSE comment down a stream that has gone quiet. Idle rather than
 * periodic, matching `Source.keepAlive` — a busy stream sends nothing extra.
 */
internal fun Sequence<String>.withKeepAlive(interval: Duration?): Sequence<String> =
    if (interval == null) this else Sequence { KeepAliveIterator(iterator(), interval) }

/** The upstream sequence ended. */
private object End

/** The upstream sequence threw; [cause] is carried across to the reader's thread. */
private class Failed(val cause: Throwable)

/**
 * Hands elements across from the thread walking the sequence, filling the
 * silence when it has nothing yet.
 */
private class KeepAliveIterator(
    upstream: Iterator<String>,
    private val interval: Duration,
) : AbstractIterator<String>(), AutoCloseable {

    private val handoff = SynchronousQueue<Any>()

    private val producer = Thread(
        {
            try {
                while (upstream.hasNext()) handoff.put(upstream.next())
                handoff.put(End)
            } catch (ignored: InterruptedException) {
                // Closed by the reader. There is nobody left to hand anything to.
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                // Everything, because this is a thread boundary: a producer
                // that died silently would leave the reader sending keep-alives
                // down a stream that has already failed.
                try {
                    handoff.put(Failed(t))
                } catch (ignored: InterruptedException) {
                    // As above: the reader gave up before it could be told.
                }
            }
        },
        "pelican-sse-keepalive",
    ).apply {
        isDaemon = true
        start()
    }

    override fun computeNext() {
        when (val taken = handoff.poll(interval.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            null -> setNext(SseOutput.KEEP_ALIVE_FRAME)
            End -> done()
            is Failed -> throw taken.cause
            else -> setNext(taken as String)
        }
    }

    /** Wakes the producing thread out of `put` so it can end. */
    override fun close() {
        producer.interrupt()
    }
}

/**
 * Frames a stream of documents as one JSON array, which core leaves to the
 * backend. The opening bracket travels with the first element rather than
 * ahead of it, so an empty stream renders `[]` and a first-element failure has
 * not yet committed to an array.
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
