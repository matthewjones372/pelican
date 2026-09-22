package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.apache.pekko.stream.javadsl.Source
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import io.github.matthewjones372.pelican.pekko.start as startOnPekko
import io.github.matthewjones372.pelican.pekko.streamedNow as streamedNowOnPekko

/**
 * What an endpoint that never ends costs, asked as a question about demand
 * rather than about the heap.
 *
 * A memory ceiling needs a forced collection to mean anything and fails on a
 * machine under load, which is how a test that guards something real ends up
 * deleted for flaking. The claim underneath it is sharper and exact: if
 * anything between the handler and the socket collected the stream, production
 * would carry on with nobody reading. So the assertion is that a stalled
 * consumer stops the producer.
 *
 * The second test exists because the first passes trivially when throughput is
 * broken — a producer that never got going also stops when the reader does.
 */
class UnboundedStreamTest {

    private companion object {
        /** Short, so several are written into the stalled socket. */
        val KEEP_ALIVE = 100.milliseconds

        /**
         * How long a settle may take before the source is presumed never to
         * settle. Only a failing run waits this out, so it is generous.
         */
        val SETTLE_TIMEOUT = 10.seconds

        /** How often the counter is read while waiting for it to stop moving. */
        const val SAMPLE_MILLIS = 50L

        /**
         * The window in which nothing may be produced — and, since 0053, the
         * stillness a settle has to observe before it may call the source
         * quiescent. One number, so the test cannot accept less than it asserts.
         */
        const val STALL_MILLIS = 500L

        const val ELEMENTS = 1_000_000L

        /**
         * How far ahead of a stalled reader the producer may get: Pekko's stage
         * buffers, the socket's write buffer and the TCP window between them.
         *
         * Set from both ends rather than guessed at either, because the failure
         * this guards against is two-sided — too low and a busy runner's buffers
         * trip it, too high and a stream nothing back-pressures slips under it.
         * Both ends are measured, in the ~1050 ms window the settle occupies:
         *
         *  - **buffered**, which this must sit above: 148,018 locally across
         *    three runs, 156,222 on a CI runner.
         *  - **collected**, which this must sit below: 7.1, 8.8 and 10.2 million
         *    — an unthrottled source into `Sink.ignore`, nothing pulling back.
         *
         * A million is the midpoint in the ratio that matters: about six times
         * the worst buffering seen, and about seven times under the slowest
         * collection seen. Its predecessor, 200,000, was 1.35× over one end and
         * 35× under the other — a number that would have flaked long before it
         * caught anything. See spec 0053.
         *
         * Unrelated to [ELEMENTS], which happens to share the value: that is how
         * many elements the *other* test drains, not a ceiling.
         */
        const val BOUNDED_AHEAD = 1_000_000L

        const val SOCKET_TIMEOUT_MILLIS = 10_000
        const val BUFFER_BYTES = 8 * 1024

        const val SEQ = "\"seq\":"
    }

    data class Tick(val seq: Long)

    private val firehose = endpoint {
        get("firehose")
        sse<Tick>(keepAlive = KEEP_ALIVE)
    }

    private val counted = endpoint {
        get("counted")
        ndjson<Tick>()
    }

    private fun api(route: ServerEndpoint): Api = api(endpoints = listOf(route), codecs = JacksonCodecs)

    /** Unbounded and unthrottled: as fast as the socket will take it. */
    private fun ticks(produced: AtomicLong) =
        Source.fromIterator { generateSequence(0L) { it + 1 }.iterator() }
            .map { n -> produced.incrementAndGet(); Tick(n) }

    @Test
    @Suppress("SleepInsteadOfDelay") // The point is a reader that stopped, measured in wall clock.
    fun `a consumer that stops reading stops the producer`() {
        val produced = AtomicLong()
        val server = api(firehose streamedNowOnPekko { ticks(produced) })
            .startOnPekko(port = 0, systemName = "unbounded-stalls")

        try {
            val uri = URI.create(server.baseUrl)
            Socket(uri.host, uri.port).use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                request(socket, uri, path = "/firehose", accept = "text/event-stream")
                readUntilFirstFrame(socket.getInputStream())

                // Nothing is read from here on. Whatever buffering exists fills
                // up, and then demand has to stop reaching the source.
                val settled = produced.settled(still = STALL_MILLIS.milliseconds, within = SETTLE_TIMEOUT)
                Thread.sleep(STALL_MILLIS)

                withClue("the source produced ${produced.get() - settled} elements with nobody reading") {
                    produced.get() shouldBe settled
                }
                withClue("the reader took one frame, so $settled ahead of it is a buffer, not back-pressure") {
                    settled shouldBeLessThan BOUNDED_AHEAD
                }
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a consumer that keeps up gets every element, in order`() {
        val produced = AtomicLong()
        val server = api(counted streamedNowOnPekko { ticks(produced).take(ELEMENTS) })
            .startOnPekko(port = 0, systemName = "unbounded-drains")

        try {
            val uri = URI.create(server.baseUrl)
            Socket(uri.host, uri.port).use { socket ->
                socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                request(socket, uri, path = "/counted", accept = "application/x-ndjson")

                val body = socket.getInputStream().bufferedReader()
                skipHeaders(body)

                // Chunk-size lines are interleaved with the frames, and a
                // dropped frame still fails the count below. Reading stops at
                // the last element rather than at EOF: the connection is
                // keep-alive, so nothing closes it when the stream ends.
                var expected = 0L
                var line: String? = body.readLine()
                while (expected < ELEMENTS && line != null) {
                    val frame = line
                    if (SEQ in frame) {
                        withClue("frames arrived out of order at element $expected") {
                            frame.substringAfter(SEQ).substringBefore('}').trim().toLong() shouldBe expected
                        }
                        expected++
                    }
                    line = body.readLine()
                }

                withClue("the stream ended early") { expected shouldBe ELEMENTS }
            }
        } finally {
            server.stop()
        }
    }

    /**
     * Settled when the counter has not moved for [still]. Two agreeing samples
     * are not enough: a scheduling pause shorter than one sample fakes them,
     * and then the assertion demands ten times that stillness. Requiring the
     * same window on both sides closes the gap. How long the wait takes is the
     * machine's business; a source that never settles is the bug this test
     * exists to catch, so the deadline fails rather than returning a number.
     */
    @Suppress("SleepInsteadOfDelay") // As above: elapsed wall clock is the measurement.
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

    private fun request(socket: Socket, uri: URI, path: String, accept: String) {
        val out = socket.getOutputStream()
        out.write(
            (
                "GET $path HTTP/1.1\r\n" +
                    "Host: ${uri.host}:${uri.port}\r\n" +
                    "Accept: $accept\r\n\r\n"
                ).toByteArray(),
        )
        out.flush()
    }

    private fun readUntilFirstFrame(input: InputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        val seen = StringBuilder()
        while (!seen.contains("data:")) {
            val read = input.read(buffer)
            check(read >= 0) { "the server ended the response before sending a frame: $seen" }
            seen.append(String(buffer, 0, read, Charsets.UTF_8))
        }
    }

    /** Chunk sizes are interleaved with the body; the seq is read out of the line either way. */
    private fun skipHeaders(body: BufferedReader) {
        while (true) {
            val line = body.readLine() ?: error("the server sent no body")
            if (line.isEmpty()) return
        }
    }
}
