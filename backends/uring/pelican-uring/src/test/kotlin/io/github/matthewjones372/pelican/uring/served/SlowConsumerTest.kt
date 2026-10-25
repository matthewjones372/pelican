package io.github.matthewjones372.pelican.uring.served

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.uring.PelicanServer
import io.github.matthewjones372.pelican.uring.start
import io.github.matthewjones372.pelican.uring.streamedNow
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A reader that stops, and one that leaves, and what each does to the handler's sequence. Pekko's answers are
 * asserted in `example`'s `SlowConsumerTest` and `UnboundedStreamTest`, over the same shapes.
 */
class SlowConsumerTest {

    data class Pulse(val seq: Long, val pad: String = "")

    private val firehose = endpoint {
        get("firehose")
        sse<Pulse>(keepAlive = KEEP_ALIVE)
    }

    private val drip = endpoint {
        get("drip")
        sse<Pulse>(keepAlive = KEEP_ALIVE)
    }

    private val flood = endpoint {
        get("flood")
        ndjson<Pulse>()
    }

    private fun api(route: ServerEndpoint): Api = api(endpoints = listOf(route), codecs = JacksonCodecs)

    /** Unbounded and unthrottled: as fast as the socket will take it. Padded, so the buffers fill in few frames. */
    private fun pulses(produced: AtomicLong) =
        generateSequence(0L) { it + 1 }.map { Pulse(produced.incrementAndGet(), PAD) }

    /** The sequence's iterator, closeable, so the test sees the interpreter close it. */
    private class Closing<T>(private val inner: Iterator<T>, private val closed: CountDownLatch) :
        Iterator<T> by inner,
        AutoCloseable {
        override fun close() = closed.countDown()
    }

    private fun <T> Sequence<T>.closingInto(closed: CountDownLatch) = Sequence { Closing(iterator(), closed) }

    @Test
    @Suppress("SleepInsteadOfDelay") // The point is a reader that stopped, measured in wall clock.
    fun `a consumer that stops reading holds the producer until it reads again`() {
        val produced = AtomicLong()
        val server = api(firehose streamedNow { pulses(produced) }).start(port = 0)

        try {
            socketTo(server, "/firehose").use { socket ->
                readUntil(socket.getInputStream(), "data:")

                // Nothing is read from here on: the buffers fill, then the producer has to wait on the writes.
                val settled = produced.settled(still = STALL_MILLIS.milliseconds, within = SETTLE_TIMEOUT)
                Thread.sleep(STALL_MILLIS)
                withClue("produced ${produced.get() - settled} elements with nobody reading") {
                    produced.get() shouldBe settled
                }
                withClue("$settled ahead of one frame read is a buffer, not back-pressure") {
                    settled shouldBeLessThan BOUNDED_AHEAD
                }

                // Held, not dead: the element after the settled one arrives once the reader takes more.
                readUntil(socket.getInputStream(), "\"seq\":${settled + 1},")
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a consumer that goes away while the stream is quiet stops the producer`() {
        val closed = CountDownLatch(1)
        // One pulse, then a wait for the next far longer than the test: only the keep-alive finds the reader gone.
        val waiting = generateSequence(0L) { n ->
            Thread.sleep(NEXT_ELEMENT_MILLIS)
            n + 1
        }.map { Pulse(it) }
        val server = api(drip streamedNow { waiting.closingInto(closed) }).start(port = 0)

        try {
            socketTo(server, "/drip").use { socket -> readUntil(socket.getInputStream(), "data:") }
            withClue("the sequence was still open after its reader went away") {
                closed.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a consumer that goes away mid-flood stops the producer`() {
        val closed = CountDownLatch(1)
        val server = api(flood streamedNow { pulses(AtomicLong()).closingInto(closed) }).start(port = 0)

        try {
            socketTo(server, "/flood").use { socket -> readUntil(socket.getInputStream(), "seq") }
            withClue("the sequence was still open after its reader went away") {
                closed.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS) shouldBe true
            }
        } finally {
            server.stop()
        }
    }

    private fun socketTo(server: PelicanServer, path: String): Socket {
        val uri = URI.create(server.baseUrl)
        val socket = Socket()
        // Fixed before connecting, so the window cannot grow while nobody reads.
        socket.receiveBufferSize = RECEIVE_BUFFER_BYTES
        socket.connect(InetSocketAddress(uri.host, uri.port))
        socket.soTimeout = SOCKET_TIMEOUT_MILLIS
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: ${uri.host}\r\n\r\n".toByteArray())
        return socket
    }

    /** Reads until [marker] has arrived; only the tail is kept, so a long read stays cheap. */
    private fun readUntil(input: InputStream, marker: String) {
        val buffer = ByteArray(BUFFER_BYTES)
        var seen = ""
        while (marker !in seen) {
            val read = input.read(buffer)
            check(read >= 0) { "the server ended the response before $marker: $seen" }
            seen = (seen + String(buffer, 0, read, Charsets.UTF_8)).takeLast(BUFFER_BYTES * 2)
        }
    }

    /** Settled once the counter has not moved for [still]; a source that never settles fails at [within]. */
    @Suppress("SleepInsteadOfDelay") // Elapsed wall clock is the measurement.
    private fun AtomicLong.settled(still: Duration, within: Duration): Long {
        val deadline = System.nanoTime() + within.inWholeNanoseconds
        var last = get()
        var since = System.nanoTime()
        while (System.nanoTime() < deadline) {
            Thread.sleep(SAMPLE_MILLIS)
            val now = get()
            if (now != last) {
                last = now
                since = System.nanoTime()
            } else if (System.nanoTime() - since >= still.inWholeNanoseconds) {
                return now
            }
        }
        fail("the source was still producing after $within with nobody reading: $last elements")
    }

    private companion object {
        val KEEP_ALIVE = 100.milliseconds
        val SETTLE_TIMEOUT = 10.seconds
        const val STALL_MILLIS = 500L
        const val SAMPLE_MILLIS = 50L
        const val NEXT_ELEMENT_MILLIS = 30_000L

        /** As `UnboundedStreamTest`'s: far above any buffering, far below a stream nothing holds back. */
        const val BOUNDED_AHEAD = 1_000_000L

        /** Generous: what is asserted is closed-or-not, not how fast. */
        const val CLOSE_TIMEOUT_SECONDS = 10L
        const val SOCKET_TIMEOUT_MILLIS = 10_000
        const val BUFFER_BYTES = 8 * 1024
        const val RECEIVE_BUFFER_BYTES = 64 * 1024
        val PAD = "x".repeat(1024)
    }
}
