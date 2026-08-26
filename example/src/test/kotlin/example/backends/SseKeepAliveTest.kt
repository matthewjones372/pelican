package example.backends

import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.apache.pekko.stream.javadsl.Source
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.time.Duration.Companion.milliseconds
import io.github.matthewjones372.pelican.pekko.start as startOnPekko
import io.github.matthewjones372.pelican.pekko.streamedNow as streamedNowOnPekko

/**
 * An SSE stream that goes quiet still says something.
 *
 * A connection producing no events is indistinguishable from a dead one, and
 * the things between a server and a browser treat it accordingly. Pekko has
 * `Source.keepAlive` and has had it all along; a backend whose stream type has
 * no equivalent builds one by hand, so the claim is asserted per backend rather
 * than once over the mechanism.
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

        const val KEEP_ALIVE = ":\n\n"
    }

    data class Tick(val n: Long)

    private val quiet = endpoint {
        get("quiet")
        sse<Tick>(keepAlive = INTERVAL)
    }

    private fun api(route: ServerEndpoint) = api(endpoints = listOf(route), codecs = JacksonCodecs)

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
                Source.from(listOf(Tick(1), Tick(2)))
                    .throttle(1, java.time.Duration.ofMillis(QUIET_MILLIS))
            },
        ).startOnPekko(port = 0, systemName = "sse-keep-alive")

        try {
            body(server.baseUrl).shouldCarryKeepAlives()
        } finally {
            server.stop()
        }
    }
}
