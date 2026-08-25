package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.apache.pekko.stream.javadsl.Source
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.milliseconds
import io.github.matthewjones372.pelican.http4k.start as startOnHttp4k
import io.github.matthewjones372.pelican.http4k.streamedNow as streamedNowOnHttp4k
import io.github.matthewjones372.pelican.ktor.start as startOnKtor
import io.github.matthewjones372.pelican.ktor.streamedNow as streamedNowOnKtor
import io.github.matthewjones372.pelican.pekko.start as startOnPekko
import io.github.matthewjones372.pelican.pekko.streamedNow as streamedNowOnPekko

/**
 * An SSE stream that goes quiet still says something, on all three backends.
 *
 * A connection producing no events is indistinguishable from a dead one, and
 * the things between a server and a browser treat it accordingly. Pekko has
 * `Source.keepAlive` and has had it all along; Ktor and http4k have nothing
 * equivalent and build it by hand — a `withTimeoutOrNull` over a rendezvous
 * channel, and a producer thread over a `SynchronousQueue` respectively. Three
 * mechanisms, one claim, which is why the claim is asserted three times here.
 *
 * The stream below sends one event, says nothing for [QUIET], then sends
 * another and ends. With a keep-alive of [INTERVAL] the quiet stretch has to
 * carry comments; without one it would carry nothing at all.
 */
class SseKeepAliveTest {

    private companion object {
        /** Short enough that several fit in the quiet stretch below. */
        val INTERVAL = 50.milliseconds

        /** Long enough that a missing keep-alive cannot be mistaken for a slow one. */
        const val QUIET_MILLIS = 600L

        /**
         * An SSE comment is a line that is nothing but a colon. An event line
         * is `data: ...`, whose colon is followed by a space, so this sequence
         * appears in the body only where a keep-alive put it.
         */
        const val KEEP_ALIVE = ":\n\n"
    }

    data class Tick(val n: Long)

    private val quiet = endpoint {
        get("quiet")
        sse<Tick>(keepAlive = INTERVAL)
    }

    private fun api(route: ServerEndpoint) = Api(endpoints = listOf(route), codecs = JacksonCodecs)

    /**
     * Read to the end rather than incrementally: the stream closes itself after
     * the second event, so the whole body is available and the assertions are
     * about what arrived rather than about when.
     */
    private fun body(baseUrl: String): String =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$baseUrl/quiet")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()

    private fun String.shouldCarryKeepAlives() {
        withClue("both events arrived: $this") {
            windowed(6).count { it.startsWith("data: ") } shouldBe 2
        }
        withClue("the quiet stretch carried comments: $this") {
            windowed(KEEP_ALIVE.length).count { it == KEEP_ALIVE } shouldBeGreaterThanOrEqual 1
        }
    }

    @Test
    fun `pekko fills the silence`() {
        val server = api(
            quiet streamedNowOnPekko {
                // `throttle` rather than an `initialDelay` on a concatenated
                // source: `concat` materialises the second source alongside the
                // first, so its timer starts before the first element is
                // written and the gap is a race. One element per interval puts
                // the silence where the test needs it.
                Source.from(listOf(Tick(1), Tick(2)))
                    .throttle(1, java.time.Duration.ofMillis(QUIET_MILLIS))
            },
        ).startOnPekko(port = 0, systemName = "sse-keep-alive")

        try {
            body(server.baseUrl).shouldCarryKeepAlives()
        } finally {
            server.stop().toCompletableFuture().join()
        }
    }

    /**
     * `Thread.sleep` rather than `delay`, although `sequence { }` is a suspend
     * block: its suspension is the generator's own and restricted, so there is
     * no dispatcher to yield to and `delay` will not compile there. Blocking is
     * also what an http4k handler does — the stream is walked on a thread the
     * server owns, which is exactly the thread this test needs to go quiet.
     */
    @Suppress("SleepInsteadOfDelay")
    @Test
    fun `http4k fills it from a thread of its own`() {
        val server = api(
            quiet streamedNowOnHttp4k {
                sequence {
                    yield(Tick(1))
                    Thread.sleep(QUIET_MILLIS)
                    yield(Tick(2))
                }
            },
        ).startOnHttp4k(port = 0)

        try {
            body(server.baseUrl).shouldCarryKeepAlives()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `and ktor from a timeout on the flow`() {
        val server = api(
            quiet streamedNowOnKtor {
                flow {
                    emit(Tick(1))
                    delay(QUIET_MILLIS)
                    emit(Tick(2))
                }
            },
        ).startOnKtor(port = 0)

        try {
            body(server.baseUrl).shouldCarryKeepAlives()
        } finally {
            server.stop()
        }
    }
}
