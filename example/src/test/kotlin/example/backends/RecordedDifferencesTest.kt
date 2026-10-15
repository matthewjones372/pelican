package example.backends

import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.pages
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import io.github.matthewjones372.pelican.jetty.handledBy as handledByOnJetty
import io.github.matthewjones372.pelican.jetty.start as startOnJetty
import io.github.matthewjones372.pelican.pekko.handledBy as handledByOnPekko
import io.github.matthewjones372.pelican.pekko.start as startOnPekko

/** The entries of [JettyDifference] that a request can show, held true on both servers. */
class RecordedDifferencesTest {

    companion object {
        private val running: Map<String, Running> = allBackends.associate { it.name to it.start(port = 0) }

        @JvmStatic
        @AfterAll
        fun stopAll() {
            running.values.forEach { it.stop() }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["/%2E%2E/x", "/hello/%2E%2E/%2e%2e/x", "//x", "/../x"])
    fun `a path escaping the root is refused by both, in words of their own`(path: String) {
        val statuses = running.mapValues { (_, server) -> statusOf(server.baseUrl, path) }

        withClue(JettyDifference.ESCAPING_PATHS.reason) {
            statuses shouldBe mapOf("pekko" to 404, "jetty" to 400)
        }
    }

    @Test
    fun `a staged handler starts on a virtual thread on jetty, and on the dispatcher on pekko`() {
        val where = endpoint { get("where"); text() }
        fun here() = CompletableFuture.completedFuture(if (Thread.currentThread().isVirtual) "virtual" else "platform")
        val pekko = api(listOf(where handledByOnPekko { here() }), JacksonCodecs)
            .startOnPekko(port = 0, systemName = "recorded-differences")
        val jetty = api(listOf(where handledByOnJetty { here() }), JacksonCodecs)
            .startOnJetty(port = 0, handlers = Handlers.onVirtualThreads)

        try {
            withClue(JettyDifference.STAGED_HANDLERS_START_VIRTUAL.reason) {
                mapOf("pekko" to bodyOf(pekko.baseUrl, "/where"), "jetty" to bodyOf(jetty.baseUrl, "/where")) shouldBe
                    mapOf("pekko" to "platform", "jetty" to "virtual")
            }
        } finally {
            pekko.stop()
            jetty.stop()
        }
    }

    @Test
    fun `a page asked for a range is a 206 on pekko, and the whole file on jetty`() {
        val where = endpoint { get("where"); text() }
        fun withPages(route: ServerEndpoint) =
            api(listOf(route), JacksonCodecs) { pages = pages("recorded-pages") }
        val pekko = withPages(where handledByOnPekko { CompletableFuture.completedFuture("") })
            .startOnPekko(port = 0, systemName = "recorded-ranges")
        val jetty = withPages(where handledByOnJetty { CompletableFuture.completedFuture("") }).startOnJetty(port = 0)

        try {
            val ranged = "Range: bytes=0-3"
            val answers = mapOf(
                "pekko" to exchange(pekko.baseUrl, "/notes.txt", ranged),
                "jetty" to exchange(jetty.baseUrl, "/notes.txt", ranged),
            )
            withClue(JettyDifference.NO_RANGES_ON_PAGES.reason + "\n" + answers) {
                answers.mapValues { (_, raw) -> raw.substringAfter(' ').substringBefore(' ').toInt() } shouldBe
                    mapOf("pekko" to 206, "jetty" to 200)
                answers.getValue("pekko").lowercase() shouldContain "content-range: bytes 0-3/10"
                answers.getValue("pekko").substringAfter("\r\n\r\n") shouldBe "abcd"
                answers.getValue("jetty").substringAfter("\r\n\r\n") shouldBe "abcdefghij"
            }
        } finally {
            pekko.stop()
            jetty.stop()
        }
    }

    /** A raw socket, because every client normalises a dot segment away before it is sent. */
    private fun exchange(baseUrl: String, path: String, header: String? = null): String {
        val uri = URI.create(baseUrl)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            socket.getOutputStream().apply {
                write(
                    (
                        "GET $path HTTP/1.1\r\nHost: ${uri.host}\r\n${header?.let { "$it\r\n" }.orEmpty()}" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII),
                )
                flush()
            }
            socket.getInputStream().readAllBytes().toString(StandardCharsets.UTF_8)
        }
    }

    private fun statusOf(baseUrl: String, path: String): Int =
        exchange(baseUrl, path).substringAfter(' ').substringBefore(' ').toInt()

    private fun bodyOf(baseUrl: String, path: String): String =
        exchange(baseUrl, path).substringAfter("\r\n\r\n").lines().first {
            it.isNotBlank() &&
                it.trim().toIntOrNull(HEX) == null
        }
}

private const val SOCKET_TIMEOUT_MILLIS = 5_000
private const val HEX = 16
