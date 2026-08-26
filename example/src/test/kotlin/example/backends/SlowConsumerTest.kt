package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.pekko.stream.javadsl.Source
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.net.Socket
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import io.github.matthewjones372.pelican.pekko.start as startOnPekko
import io.github.matthewjones372.pelican.pekko.streamedNow as streamedNowOnPekko

/**
 * A consumer that takes one frame and walks away, and what the handler's stream
 * does about it.
 *
 * Nothing anywhere asked this before, and a stream nobody is reading is where a
 * leak lives: `StreamingSunHttp` writes on a thread from an unbounded pool, and
 * the keep-alive that makes an idle SSE connection legible is a second thread
 * behind it. The consumer below reads one frame, stops reading while several
 * keep-alives are written into its socket, and then hangs up — so the server is
 * writing to a socket that is gone, which is the only way it finds out.
 *
 * What is asserted is the handler-side signal, because that is the one thing a
 * backend can be asked for in its own terms: `watchTermination` on a `Source`.
 * Thread counts are a clue on failure and not an assertion: a pool keeps
 * threads for reasons of its own, and a test that counts them fails on somebody
 * else's timing.
 */
class SlowConsumerTest {

    private companion object {
        /** Short, so several are written into the abandoned socket. */
        val KEEP_ALIVE = 100.milliseconds

        /** Several keep-alive intervals of reading nothing. */
        const val STALL_MILLIS = 500L

        /** Longer than the test, so the stream never ends of its own accord. */
        const val NEXT_ELEMENT_MILLIS = 30_000L

        /** Generous: what is being measured is closed-or-not, not how fast. */
        const val CLOSE_TIMEOUT_SECONDS = 10L

        const val SOCKET_TIMEOUT_MILLIS = 5_000
    }

    data class Tick(val n: Long)

    private val drip = endpoint {
        get("drip")
        sse<Tick>(keepAlive = KEEP_ALIVE)
    }

    private fun api(route: ServerEndpoint): Api = api(endpoints = listOf(route), codecs = JacksonCodecs)

    @Test
    fun `pekko closes the source when the consumer disappears`() {
        val closed = CountDownLatch(1)
        val server = api(
            drip streamedNowOnPekko {
                Source.range(1, Int.MAX_VALUE)
                    .throttle(1, java.time.Duration.ofMillis(NEXT_ELEMENT_MILLIS))
                    .map { n -> Tick(n.toLong()) }
                    .watchTermination { mat, done ->
                        done.whenComplete { _, _ -> closed.countDown() }
                        mat
                    }
            },
        ).startOnPekko(port = 0, systemName = "slow-consumer")

        try {
            readOneFrameThenVanish(server.baseUrl)
            closed.shouldFireWithinTheTimeout("the source")
        } finally {
            server.stop()
        }
    }

    /**
     * One frame, then nothing. A raw socket rather than a client, because a
     * client is free to drain what is still arriving or to keep the connection
     * for its pool, and either would make this test about the client.
     */
    @Suppress("SleepInsteadOfDelay") // As above: the point is a reader that stopped.
    private fun readOneFrameThenVanish(baseUrl: String) {
        val uri = URI.create(baseUrl)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val out = socket.getOutputStream()
            out.write(
                (
                    "GET /drip HTTP/1.1\r\n" +
                        "Host: ${uri.host}:${uri.port}\r\n" +
                        "Accept: text/event-stream\r\n\r\n"
                    ).toByteArray(),
            )
            out.flush()

            readUntilFirstFrame(socket.getInputStream())

            // Nothing is read from here on. The keep-alives pile up in the
            // socket's buffer, and closing it below is what turns the next one
            // into the write failure the server has to notice.
            Thread.sleep(STALL_MILLIS)
        }
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

    private fun CountDownLatch.shouldFireWithinTheTimeout(what: String) {
        val fired = await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        withClue("$what was still open ${CLOSE_TIMEOUT_SECONDS}s after the consumer went away; ${liveThreads()}") {
            fired shouldBe true
        }
    }

    /** A clue on failure, never an assertion: see this class's own note. */
    private fun liveThreads(): String {
        val names = Thread.getAllStackTraces().keys.map { it.name }
        return "${names.size} live threads, of which ${names.count { "pelican" in it }} are Pelican's own"
    }
}

private const val BUFFER_BYTES = 512
