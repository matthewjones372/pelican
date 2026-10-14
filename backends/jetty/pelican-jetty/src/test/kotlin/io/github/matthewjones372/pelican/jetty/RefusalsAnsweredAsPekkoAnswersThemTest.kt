package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.ApiErrorEnvelope
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.ProblemDetails
import io.github.matthewjones372.pelican.RefusalObserver
import io.github.matthewjones372.pelican.RefusalRenderer
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.before
import io.github.matthewjones372.pelican.cookieParam
import io.github.matthewjones372.pelican.cors
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.forbidden
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.negotiated
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.responseHeader
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.KType
import io.github.matthewjones372.pelican.pekko.handledNow as pekkoHandledNow
import io.github.matthewjones372.pelican.pekko.handledOrFail as pekkoHandledOrFail
import io.github.matthewjones372.pelican.pekko.start as pekkoStart

/** Filters, refusals in both shipped envelopes, the refusal observer, a broken handler and negotiation. */
class RefusalsAnsweredAsPekkoAnswersThemTest {

    data class Said(val text: String)

    data class Echoed(val text: String, val trace: String?)

    data class Strictly(val term: String, val key: String, val jar: String)

    data class Widget(val id: Long)

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

    /** A JSON codec, and a `Widget` written as CSV for the one media type JSON cannot answer. */
    private object WidgetCodecs : Codecs by JacksonCodecs {
        @Suppress("UNCHECKED_CAST")
        override fun <T> codec(type: KType, mediaType: String): BodyCodec<T> =
            if (mediaType != "text/csv") JacksonCodecs.codec(type, mediaType)
            else object : BodyCodec<Widget> {
                override fun encodeToString(value: Widget): String = "id\n${value.id}\n"
                override fun decodeFromString(text: String): Widget = Widget(text.lines()[1].toLong())
            } as BodyCodec<T>
    }

    /** Both servers in one envelope, each with its own observer. */
    class Servers(val name: String, val jetty: String, val pekko: String) {
        override fun toString() = name
    }

    companion object {
        private val traceId = headerParam<String>("X-Trace-Id").optional()
        private val requestId = responseHeader<String>("X-Request-Id", "Correlates this answer")

        private val who = pathParam<String>("who")
        private val greet = endpoint(who) {
            get("hello" / who)
            emits(requestId)
            text()
        }

        private val echo = endpoint(traceId, jsonBody<Said>()) {
            post("echo")
            emits(requestId)
            json<Echoed>()
        }

        private val strict = endpoint(
            queryParam<String>("term"),
            headerParam<String>("X-Key"),
            cookieParam<String>("jar"),
        ) {
            get("strict")
            emits(requestId)
            json<Strictly>()
        }

        private val from = pathParam<Int>("from")
        private val count = endpoint(from) {
            get("count" / from)
            emits(requestId)
            text()
        }

        private val export = endpoint {
            get("export")
            emits(requestId)
            negotiated(json<Widget>(status = 200), media<Widget>("text/csv", status = 200))
        }

        private val teapotish = errorJson<Widget>(419, "A legal status nobody registered")
        private val odd = endpoint {
            get("odd")
            emits(requestId)
            json<Widget>() orFail teapotish
        }

        private val nothing = endpoint {
            get("nothing")
            emits(requestId)
            empty(status = 204)
        }

        private val problem = endpoint {
            get("problem")
            emits(requestId)
            media<Widget>("application/problem+json")
        }

        private val stamping = Filter { params, next ->
            params.setHeader(requestId, (if (traceId in params) params[traceId] else null) ?: "generated")
            next(params)
        }

        private val gate = before { params ->
            if (traceId in params && params[traceId] == "blocked") forbidden("This trace id is refused by the gate")
        }

        private fun echoed(trace: String?, said: Said): Echoed =
            if (said.text == "boom") error("the note that broke the handler") else Echoed(said.text, trace)

        private val jettyRoutes: List<ServerEndpoint> = listOf(
            greet handledNow { name -> "Hello, $name!" },
            echo handledNow { (trace, said) -> echoed(trace, said) },
            strict handledNow { (term, key, jar) -> Strictly(term, key, jar) },
            count handledNow { n -> "from $n" },
            export handledNow { Widget(7) },
            odd handledOrFail { teapotish(Widget(2)) },
            nothing handledNow { },
            problem handledNow { Widget(3) },
        )

        private val pekkoRoutes: List<ServerEndpoint> = listOf(
            greet pekkoHandledNow { name -> "Hello, $name!" },
            echo pekkoHandledNow { (trace, said) -> echoed(trace, said) },
            strict pekkoHandledNow { (term, key, jar) -> Strictly(term, key, jar) },
            count pekkoHandledNow { n -> "from $n" },
            export pekkoHandledNow { Widget(7) },
            odd pekkoHandledOrFail { teapotish(Widget(2)) },
            nothing pekkoHandledNow { },
            problem pekkoHandledNow { Widget(3) },
        )

        /** What each server's observer was told, in the order it was told. */
        private val observed: Map<String, ConcurrentLinkedQueue<String>> =
            listOf("jetty", "pekko").flatMap { s -> listOf("envelope", "problem").map { "$s/$it" } }
                .associateWith { ConcurrentLinkedQueue<String>() }

        private fun served(routes: List<ServerEndpoint>, renderer: RefusalRenderer, server: String) =
            api(routes, WidgetCodecs) {
                refusals(renderer)
                onRefusal(
                    RefusalObserver { reason, status, template ->
                        observed.getValue(server) += "${reason.label} $status ${template ?: "-"}"
                    },
                )
                cors = cors("https://console.example.com")
                filter(stamping)
                filter(gate)
                maxBodyBytes = 4_096
            }

        private val dialects = mapOf("envelope" to ApiErrorEnvelope, "problem" to ProblemDetails)

        private val jetty = dialects.mapValues { (name, renderer) ->
            served(jettyRoutes, renderer, "jetty/$name").start(port = 0)
        }
        private val pekko = dialects.mapValues { (name, renderer) ->
            served(pekkoRoutes, renderer, "pekko/$name").pekkoStart(port = 0)
        }

        @JvmStatic
        fun envelopes(): List<Servers> =
            dialects.keys.map { Servers(it, jetty.getValue(it).baseUrl, pekko.getValue(it).baseUrl) }

        private const val JSON = "application/json"
        private fun note(text: String) = """{"text":"$text"}"""
        private fun jsonFrom(trace: String) = mapOf("X-Trace-Id" to trace, "Content-Type" to JSON)

        @JvmStatic
        fun asked(): List<Asked> = listOf(
            Asked("a header a filter sets", "GET", "/hello/ada"),
            Asked("a header a filter reads", "POST", "/echo", jsonFrom("t-1"), note("hi")),
            Asked("a filter that refuses", "POST", "/echo", jsonFrom("blocked"), note("hi")),
            Asked("a handler that throws", "POST", "/echo", mapOf("Content-Type" to JSON), note("boom")),
            Asked("a body that is not json", "POST", "/echo", mapOf("Content-Type" to JSON), "{ nope"),
            Asked("a body past the limit", "POST", "/echo", mapOf("Content-Type" to JSON), note("x".repeat(8_192))),
            Asked("a content type nothing reads", "POST", "/echo", mapOf("Content-Type" to "application/xml"), "<x/>"),
            Asked("a capture that will not decode", "GET", "/count/not-a-number"),
            Asked("a query left off", "GET", "/strict", mapOf("X-Key" to "k", "Cookie" to "jar=j")),
            Asked("a header left off", "GET", "/strict?term=t", mapOf("Cookie" to "jar=j")),
            Asked("a cookie left off", "GET", "/strict?term=t", mapOf("X-Key" to "k")),
            Asked("an accept taking nothing sent", "GET", "/strict?term=t", mapOf("Accept" to "text/csv")),
            Asked(
                "a preflight refused before any route",
                "OPTIONS",
                "/echo",
                mapOf("Origin" to "https://not.allowed.example", "Access-Control-Request-Method" to "POST"),
            ),
            Asked("a negotiated answer as json", "GET", "/export", mapOf("Accept" to JSON)),
            Asked("a negotiated answer as csv", "GET", "/export", mapOf("Accept" to "text/csv")),
            Asked("a negotiated answer with no accept", "GET", "/export"),
            Asked("a preference by q", "GET", "/export", mapOf("Accept" to "$JSON;q=0.2, text/csv;q=0.9")),
            Asked("a named type over a wildcard", "GET", "/export", mapOf("Accept" to "*/*;q=0.1, text/csv")),
            Asked("a negotiated answer refused", "GET", "/export", mapOf("Accept" to "application/xml")),
            Asked("a status nobody registered", "GET", "/odd"),
            Asked("no representation to refuse", "GET", "/nothing", mapOf("Accept" to "application/xml")),
            Asked("a +json media type", "GET", "/problem", mapOf("Accept" to "application/problem+json")),
        )

        @JvmStatic
        fun cases(): List<Array<Any>> = envelopes().flatMap { servers -> asked().map { arrayOf(servers, it) } }

        private val asking = Asking(listOf("Content-Type", "X-Request-Id", "Access-Control-Allow-Origin", "Vary"))

        /** Twelve hex characters, which is what core mints for each unexpected failure. */
        private val REFERENCE = Regex("Reference: [0-9a-f]{12}")

        @JvmStatic
        @AfterAll
        fun stopAll() {
            asking.close()
            jetty.values.forEach { it.stop() }
            pekko.values.forEach { it.stop() }
        }
    }

    private fun Asked.of(baseUrl: String): Answer {
        val answer = asking.ask(baseUrl, method, path, headers, body)
        return answer.copy(body = answer.body.replace(REFERENCE, "Reference: <minted>"))
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("cases")
    fun `jetty answers as pekko does`(servers: Servers, asked: Asked) {
        withClue(asked) { asked.of(servers.jetty) shouldBe asked.of(servers.pekko) }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("envelopes")
    fun `the same refusals are observed the same way on both`(servers: Servers) {
        val refusals = asked().filter { it.name != "a header a filter sets" && it.name != "a header a filter reads" }
        val seen = listOf("jetty" to servers.jetty, "pekko" to servers.pekko).associate { (server, baseUrl) ->
            val log = observed.getValue("$server/${servers.name}")
            log.clear()
            refusals.forEach { it.of(baseUrl) }
            server to log.toList()
        }

        seen.getValue("jetty") shouldBe seen.getValue("pekko")
        seen.getValue("jetty") shouldContain "accept 406 /strict"
    }

    @Test
    fun `a handler that throws answers a 500 naming a reference, and nothing of the throwable`() {
        val answer =
            asking.ask(jetty.getValue("problem").baseUrl, "POST", "/echo", mapOf("Content-Type" to JSON), note("boom"))

        answer.status shouldBe 500
        answer.headers["Content-Type"] shouldBe "application/problem+json"
        answer.headers["X-Request-Id"] shouldBe "generated"
        answer.body shouldMatch Regex(".*Reference: [0-9a-f]{12}.*")
        answer.body shouldNotContain "broke"
    }

    @Test
    fun `a negotiated answer goes out in the rendering the caller asked for`() {
        val answer = asking.ask(jetty.getValue("envelope").baseUrl, "GET", "/export", mapOf("Accept" to "text/csv"))

        answer.body shouldBe "id\n7\n"
        answer.headers["Content-Type"] shouldBe "text/csv"
    }
}
