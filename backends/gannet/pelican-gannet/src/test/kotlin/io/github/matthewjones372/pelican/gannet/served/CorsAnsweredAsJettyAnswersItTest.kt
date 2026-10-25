package io.github.matthewjones372.pelican.gannet.served

import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.cors
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.gannet.RequiresGannet
import io.github.matthewjones372.pelican.gannet.bytesNow
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.start
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.optional
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import io.github.matthewjones372.pelican.jetty.bytesNow as jettyBytesNow
import io.github.matthewjones372.pelican.jetty.handledNow as jettyHandledNow
import io.github.matthewjones372.pelican.jetty.start as jettyStart

/** CORS on both servers: the preflight, and the headers on every answer an endpoint gives. */
@RequiresGannet
class CorsAnsweredAsJettyAnswersItTest {

    data class Note(val text: String)

    data class Echoed(val text: String, val trace: String?)

    /** One request, named so a failing case says which. */
    data class Asked(
        val name: String,
        val method: String,
        val path: String,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
    ) {
        override fun toString() = name
    }

    companion object {
        private const val ALLOWED = "https://console.example.com"
        private const val REJECTED = "https://not-the-console.example.com"

        private val traceId = headerParam<String>("X-Trace-Id").optional()

        private val echo = endpoint(traceId, jsonBody<Note>()) {
            post("echo")
            json<Echoed>()
        }

        private val logo = endpoint {
            get("logo")
            bytes("image/png")
        }

        private fun served(endpoints: List<ServerEndpoint>) = api(endpoints, JacksonCodecs) { cors = cors(ALLOWED) }

        private val gannet = served(
            listOf(
                echo handledNow { (trace, note) -> Echoed(note.text, trace) },
                logo bytesNow { sequenceOf("PELICAN".toByteArray()) },
            ),
        ).start(port = 0)

        private val jetty = served(
            listOf(
                echo jettyHandledNow { (trace, note) -> Echoed(note.text, trace) },
                logo jettyBytesNow { sequenceOf("PELICAN".toByteArray()) },
            ),
        ).jettyStart(port = 0)

        private val asking = Asking(
            listOf(
                "Content-Type",
                "Allow",
                "Access-Control-Allow-Origin",
                "Access-Control-Allow-Methods",
                "Access-Control-Allow-Headers",
                "Access-Control-Max-Age",
                "Vary",
            ),
        )

        private fun preflight(from: String, method: String = "POST") =
            mapOf("Origin" to from, "Access-Control-Request-Method" to method)

        private val fromBrowser = mapOf("Origin" to ALLOWED, "Content-Type" to "application/json")
        private const val NOTE = """{"text":"hi"}"""

        @JvmStatic
        fun asked(): List<Asked> = listOf(
            Asked("an allowed preflight", "OPTIONS", "/echo", preflight(ALLOWED)),
            Asked("a preflight from an origin not named", "OPTIONS", "/echo", preflight(REJECTED)),
            Asked("a preflight for a method the path lacks", "OPTIONS", "/echo", preflight(ALLOWED, "DELETE")),
            Asked("a preflight for a path nobody described", "OPTIONS", "/nobody", preflight(ALLOWED)),
            Asked("an options that is no preflight", "OPTIONS", "/echo"),
            Asked("a cross-origin call", "POST", "/echo", fromBrowser + ("X-Trace-Id" to "t-1"), NOTE),
            Asked("a streamed answer", "GET", "/logo", fromBrowser),
            Asked("a head at a streamed endpoint", "HEAD", "/logo", fromBrowser),
            Asked("an error", "POST", "/echo", fromBrowser, "not json"),
            Asked("an origin not named", "POST", "/echo", fromBrowser + ("Origin" to REJECTED), NOTE),
            Asked("a call that is not cross-origin", "POST", "/echo", fromBrowser - "Origin", NOTE),
            Asked("a verb nobody declared", "PUT", "/echo", fromBrowser),
            Asked("a path nobody described", "GET", "/nobody", fromBrowser),
        )

        @JvmStatic
        @AfterAll
        fun stopBoth() {
            asking.close()
            gannet.stop()
            jetty.stop()
        }
    }

    private fun Asked.of(baseUrl: String): Answer = asking.ask(baseUrl, method, path, headers, body)

    @ParameterizedTest(name = "{0}")
    @MethodSource("asked")
    fun `gannet answers as jetty does`(asked: Asked) {
        withClue(asked) { asked.of(gannet.baseUrl) shouldBe asked.of(jetty.baseUrl) }
    }

    @Test
    fun `a preflight is answered from the description of that path`() {
        val answer = asking.ask(gannet.baseUrl, "OPTIONS", "/echo", preflight(ALLOWED))

        answer.status shouldBe 204
        answer.headers["Access-Control-Allow-Origin"] shouldBe ALLOWED
        answer.headers["Access-Control-Allow-Methods"] shouldBe "POST"
        answer.headers["Access-Control-Allow-Headers"] shouldBe "X-Trace-Id, Content-Type"
        answer.headers["Access-Control-Max-Age"] shouldBe "600"
    }

    @Test
    fun `a method that path never describes is refused, with nothing leaked`() {
        val answer = asking.ask(gannet.baseUrl, "OPTIONS", "/echo", preflight(ALLOWED, "DELETE"))

        answer.status shouldBe 403
        answer.body shouldContain "DELETE"
        answer.headers["Access-Control-Allow-Origin"].shouldBeNull()
    }

    @Test
    fun `an error carries the headers, or the browser hides why the call failed`() {
        val answer = asking.ask(gannet.baseUrl, "POST", "/echo", fromBrowser, "not json")

        answer.status shouldBe 400
        answer.headers["Access-Control-Allow-Origin"] shouldBe ALLOWED
        answer.headers["Vary"] shouldBe "Origin"
    }
}
