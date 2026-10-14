package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.StreamIn
import io.github.matthewjones372.pelican.spi.NdjsonFrames
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.util.thread.Invocable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Pekko's `StreamConverters.asInputStream` default: a blocking read waits this long for the next bytes. */
internal const val READ_TIMEOUT_MILLIS = 5_000L

internal class JettyFrames<T>(private val source: Content.Source, private val frames: NdjsonFrames) : StreamIn<T> {
    private val opened = AtomicBoolean(false)

    fun open(): FrameReader<T> {
        check(opened.compareAndSet(false, true)) { "This upload has already been read." }
        return FrameReader(TimedInput(source, READ_TIMEOUT_MILLIS), frames)
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

    /** Fails the request's content so a read blocked on it, on another thread, throws rather than waits. */
    fun wake() = input.wake()

    /** Gives back what this reader holds; the rest of an upload it stopped reading is left unread. */
    override fun close() = input.close()
}

/** The frames of an `ndjsonIn` body, read lazily as the sequence is iterated, at most once. */
fun <T> StreamIn<T>.toSequence(): Sequence<T> = Sequence { reader() }.constrainOnce()

/** The same frames as an iterator that can be closed early or woken from another thread. */
@Suppress("UNCHECKED_CAST")
fun <T> StreamIn<T>.reader(): FrameReader<T> = (this as JettyFrames<T>).open()

/**
 * A request body as a stream whose read gives up after [timeoutMillis] with no bytes arriving, as Pekko's does.
 * `Content.Source.asInputStream` would wait for the connector's idle timeout instead.
 */
internal class TimedInput(private val source: Content.Source, private val timeoutMillis: Long) : InputStream() {
    private var chunk: Content.Chunk? = null

    @Volatile
    private var arrived: CountDownLatch? = null

    private val woken = AtomicBoolean(false)

    @Volatile
    private var ended = false

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
    }

    override fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val current = chunk ?: next()
            val buffer = current.byteBuffer
            if (buffer.hasRemaining()) {
                val read = minOf(length, buffer.remaining())
                buffer.get(into, offset, read)
                return read
            }
            if (current.isLast) return -1
            current.release()
            chunk = null
        }
    }

    private fun next(): Content.Chunk {
        while (true) {
            if (woken.get()) throw IOException("The upload was closed before it was read")
            val read = source.read()
            if (read != null) {
                if (Content.Chunk.isFailure(read)) throw read.failure as? IOException ?: IOException(read.failure)
                chunk = read
                ended = read.isLast
                return read
            }
            val latch = CountDownLatch(1)
            arrived = latch
            source.demand(Invocable.from(Invocable.InvocationType.NON_BLOCKING, latch::countDown))
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS) && !woken.get()) {
                val timedOut = IOException("Timeout on waiting for new data")
                source.fail(timedOut)
                throw timedOut
            }
        }
    }

    fun wake() {
        if (woken.compareAndSet(false, true) && !ended) source.fail(IOException("The upload was closed"))
        arrived?.countDown()
    }

    override fun close() {
        wake()
        chunk?.release()
        chunk = Content.Chunk.EOF
    }
}

private const val BYTE_MASK = 0xFF
