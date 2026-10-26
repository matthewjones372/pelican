package example.backends

import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
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

/**
 * Percent-escapes that are not UTF-8 are the caller's 400 on every backend, in one sentence (spec 0089, decision 5),
 * rather than U+FFFD served on one, a 500 on another and a silently dropped key on a third.
 */
class StrictDecodingTest {

    companion object {
        private val running: Map<String, Running> = runnableBackends.associate { it.name to it.start(port = 0) }

        private val clients: Map<String, ApiClient> =
            running.mapValues { (_, server) -> apiClient(server.baseUrl, JacksonCodecs) }

        private const val NOT_UTF8 = """{"status":400,"error":"Illegal query string",""" +
            """"detail":"The percent-encoded bytes are not UTF-8"}"""

        @JvmStatic
        @AfterAll
        fun stopAll() {
            clients.values.forEach { it.close() }
            running.values.forEach { it.stop() }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "/items/x?q=%e9", "/items/x?%f0", "/items/x?other=%e9", "/items/x?%e9=x", "/items/x?q=%c3",
            "/items/x?q=%c0%af", "/items/x?q=%ed%a0%80", "/items/x?q=ok&q=%e9",
        ],
    )
    fun `escapes that are not UTF-8 are the same 400 everywhere, whichever pair carries them`(target: String) {
        val answers = running.mapValues { (_, server) -> exchange(server.baseUrl, target) }

        withClue("GET $target\n$answers") {
            answers.values.toSet() shouldBe setOf("400 $NOT_UTF8")
        }
    }

    @Test
    fun `UTF-8 written as escapes is the text it spells everywhere`() {
        val target = "/items/x?q=%C3%A9%E2%82%AC%F0%9F%98%80"
        val answers = running.mapValues { (_, server) -> exchange(server.baseUrl, target) }

        withClue(answers) { answers.values.toSet() shouldBe setOf("""200 {"fromPath":"x","fromQuery":"é€😀"}""") }
    }

    @Test
    fun `a form body whose escapes are not UTF-8 is the same 400 everywhere`() {
        val answers = clients.mapValues { (_, client) ->
            client.transport.send(
                client.request(signIn, SignIn("ada", remember = false, visits = 0))
                    .withBody("user=%e9&remember=on&visits=3"),
            ).let { "${it.status} ${it.body}" }
        }

        withClue(answers) {
            answers.values.toSet().size shouldBe 1
            answers.values.first() shouldContain "400 "
            answers.values.first() shouldContain "The percent-encoded bytes are not UTF-8"
        }
    }

    /** A raw socket: `java.net.URI` will not hold every target asked about here. */
    private fun exchange(baseUrl: String, target: String): String {
        val uri = URI.create(baseUrl)
        return Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            socket.getOutputStream().apply {
                write("GET $target HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val raw = socket.getInputStream().readAllBytes().toString(StandardCharsets.UTF_8)
            raw.substringAfter(' ').substringBefore(' ') + " " + raw.substringAfter("\r\n\r\n")
        }
    }
}

private const val SOCKET_TIMEOUT_MILLIS = 5_000
