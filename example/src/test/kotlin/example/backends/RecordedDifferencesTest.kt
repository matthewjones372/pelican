package example.backends

import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.gannet.GannetTests
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pages
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import io.github.matthewjones372.pelican.gannet.handledBy as handledByOnGannet
import io.github.matthewjones372.pelican.gannet.start as startOnGannet
import io.github.matthewjones372.pelican.jetty.handledBy as handledByOnJetty
import io.github.matthewjones372.pelican.jetty.start as startOnJetty
import io.github.matthewjones372.pelican.pekko.handledBy as handledByOnPekko
import io.github.matthewjones372.pelican.pekko.start as startOnPekko

/** The entries of [JettyDifference] and [GannetDifference] that a request can show, held true on every server. */
class RecordedDifferencesTest {

    companion object {
        private val running: Map<String, Running> = runnableBackends.associate { it.name to it.start(port = 0) }

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

        withClue(JettyDifference.ESCAPING_PATHS.reason + "\n" + GannetDifference.ESCAPING_PATHS.reason) {
            statuses shouldBe mapOf("pekko" to 404, "jetty" to 400, "gannet" to 404).filterKeys { it in running }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["/items/x?q=%zz", "/items/x?other=%4"])
    fun `an illegal escape in a query is a 400 everywhere, and pekko's is in words of its own`(target: String) {
        val answers = running.mapValues { (_, server) -> exchange(server.baseUrl, target) }

        withClue(JettyDifference.ILLEGAL_ESCAPES_IN_QUERY.reason + "\n" + answers) {
            answers.values.map { it.substringAfter(' ').substringBefore(' ') }.toSet() shouldBe setOf("400")
            answers["pekko"]?.substringAfter("\r\n\r\n")?.shouldStartWith("Illegal request-target")
            listOfNotNull(answers["jetty"], answers["gannet"])
                .map { it.substringAfter("\r\n\r\n") }
                .forEach { it shouldStartWith """{"status":400,"error":"Illegal query string","detail":"URLDecoder""" }
        }
    }

    @Test
    fun `a page asked for a range is a 206 on pekko, and the whole file on jetty and gannet`() {
        val where = endpoint { get("where"); text() }
        fun withPages(route: ServerEndpoint) =
            api(listOf(route), JacksonCodecs) { pages = pages("recorded-pages") }
        val pekko = withPages(where handledByOnPekko { CompletableFuture.completedFuture("") })
            .startOnPekko(port = 0, systemName = "recorded-ranges")
        val jetty = withPages(where handledByOnJetty { CompletableFuture.completedFuture("") }).startOnJetty(port = 0)
        val gannet = if (GannetTests.run) {
            withPages(where handledByOnGannet { CompletableFuture.completedFuture("") }).startOnGannet(port = 0)
        } else {
            null
        }

        try {
            val ranged = "Range: bytes=0-3"
            val answers = mapOf(
                "pekko" to exchange(pekko.baseUrl, "/notes.txt", ranged),
                "jetty" to exchange(jetty.baseUrl, "/notes.txt", ranged),
            ) + listOfNotNull(gannet?.let { "gannet" to exchange(it.baseUrl, "/notes.txt", ranged) })
            withClue(JettyDifference.NO_RANGES_ON_PAGES.reason + "\n" + answers) {
                answers.mapValues { (_, raw) -> raw.substringAfter(' ').substringBefore(' ').toInt() } shouldBe
                    mapOf("pekko" to 206, "jetty" to 200, "gannet" to 200).filterKeys { it in answers }
                answers.getValue("pekko").lowercase() shouldContain "content-range: bytes 0-3/10"
                answers.getValue("pekko").substringAfter("\r\n\r\n") shouldBe "abcd"
                answers.getValue("jetty").substringAfter("\r\n\r\n") shouldBe "abcdefghij"
                if (gannet != null) answers.getValue("gannet").substringAfter("\r\n\r\n") shouldBe "abcdefghij"
            }
        } finally {
            pekko.stop()
            jetty.stop()
            gannet?.stop()
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
}

private const val SOCKET_TIMEOUT_MILLIS = 5_000
