package io.github.matthewjones372.pelican.uring

import io.github.matthewjones372.pelican.StreamIn
import io.github.matthewjones372.pelican.spi.NdjsonFrames
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** Pekko's `StreamConverters.asInputStream` default: a blocking read waits this long for the next bytes. */
internal const val READ_TIMEOUT_MILLIS = 5_000L

internal class UringFrames<T>(private val body: RequestBody, private val frames: NdjsonFrames) : StreamIn<T> {
    private val opened = AtomicBoolean(false)

    fun open(): FrameReader<T> {
        check(opened.compareAndSet(false, true)) { "This upload has already been read." }
        return FrameReader(TimedInput(body, READ_TIMEOUT_MILLIS), frames)
    }
}

/**
 * The frames of an upload, each read and decoded as it is asked for. Blocking: for a handler on a virtual thread.
 * A frame that will not decode, or is longer than `maxFrameBytes`, throws from [next] and so fails the handler.
 */
class FrameReader<T> internal constructor(
    private val input: TimedInput,
    frames: NdjsonFrames,
) : Iterator<T>, AutoCloseable {

    @Suppress("UNCHECKED_CAST")
    private val values = frames.readFrom(input).iterator() as Iterator<T>

    override fun hasNext(): Boolean = values.hasNext()

    override fun next(): T {
        if (!values.hasNext()) throw NoSuchElementException("The upload has no more frames.")
        return values.next()
    }

    /** Fails the request's body so a read blocked on it, on another thread, throws rather than waits. */
    fun wake() = input.wake()

    /** Gives back what this reader holds; the rest of an upload it stopped reading is left unread. */
    override fun close() = input.close()
}

/** The frames of an `ndjsonIn` body, read lazily as the sequence is iterated, at most once. */
fun <T> StreamIn<T>.toSequence(): Sequence<T> = Sequence { reader() }.constrainOnce()

/** The same frames as an iterator that can be closed early or woken from another thread. */
@Suppress("UNCHECKED_CAST")
fun <T> StreamIn<T>.reader(): FrameReader<T> = (this as UringFrames<T>).open()

/**
 * A request body as a stream whose read gives up after [timeoutMillis] with no bytes arriving, as Pekko's does,
 * rather than waiting for the connection's idle timeout. Read off the carrier, where the runtime applies the timeout.
 */
internal class TimedInput(private val body: RequestBody, timeoutMillis: Long) : InputStream() {
    private val timeout = Duration.ofMillis(timeoutMillis)
    private val woken = AtomicBoolean(false)

    @Volatile
    private var ended = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
    }

    override fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (ended) return -1
        if (woken.get()) throw IOException("The upload was closed before it was read")
        return body.read(into, offset, length, timeout).also { if (it < 0) ended = true }
    }

    fun wake() {
        if (woken.compareAndSet(false, true) && !ended) body.fail(IOException("The upload was closed"))
    }

    /**
     * Stops reading without failing the body: the runtime drains what is left after the reply, so the connection
     * lives, rather than closing it on unread bytes and resetting the reply on its way out.
     */
    override fun close() {
        ended = true
    }
}

private const val BYTE_MASK = 0xFF
