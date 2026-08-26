package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.negotiated
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.reflect.KType
import io.github.matthewjones372.pelican.pekko.handledNow as handledNowOnPekko
import io.github.matthewjones372.pelican.pekko.start as startOnPekko

/**
 * Pelican resolves its own codecs and never goes through a `Marshaller`, a
 * `ContentConverter` or a `BiDiBodyLens`, so nothing underneath it reads
 * `Accept`.
 *
 * `java.net.http` rather than this library's own client, which sends an
 * `Accept` of its own: the header under test has to be the one written here.
 */
class ContentNegotiationTest {

    data class Widget(val id: Long)

    private val widget = endpoint { get("widget"); json<Widget>() }

    /** Legal, unregistered, and the one Pekko used to answer 500 for. */
    private val odd = endpoint { get("odd"); json<Widget>(status = 419) }

    /** No representation at all, so there is nothing for a caller to refuse. */
    private val nothing = endpoint { get("nothing"); empty(status = 204) }

    /** One widget, two wire shapes: the same value under one status. */
    private val export = endpoint {
        get("export")
        negotiated(json<Widget>(status = 200), media<Widget>("text/csv", status = 200))
    }

    /**
     * Jackson, plus the one thing no JSON library answers: a `Widget` written
     * as CSV. A second representation is an encoder the service supplies, and
     * this is the whole of what supplying one looks like — the description
     * names `text/csv`, and the `Codecs` says what that means.
     */
    private object WidgetCodecs : Codecs by JacksonCodecs {
        @Suppress("UNCHECKED_CAST")
        override fun <T> codec(type: KType, mediaType: String): BodyCodec<T> =
            if (mediaType != "text/csv") JacksonCodecs.codec(type, mediaType)
            else object : BodyCodec<Widget> {
                override fun encodeToString(value: Widget): String = "id\n${value.id}\n"
                override fun decodeFromString(text: String): Widget = Widget(text.lines()[1].toLong())
            } as BodyCodec<T>
    }

    private fun api(routes: List<ServerEndpoint>) = api(endpoints = routes, codecs = WidgetCodecs)

    private val client: HttpClient = HttpClient.newHttpClient()

    private fun get(baseUrl: String, path: String, accept: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            .let { if (accept == null) it else it.header("Accept", accept) }
            .GET()
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun probe(baseUrl: String) {
        withClue("no Accept at all asks for nothing in particular") {
            get(baseUrl, "/widget", accept = null).statusCode() shouldBe 200
        }
        withClue("a caller that takes anything takes JSON") {
            get(baseUrl, "/widget", accept = "*/*").statusCode() shouldBe 200
        }
        withClue("a browser's header ends in a wildcard, so it takes JSON too") {
            get(baseUrl, "/widget", accept = "text/html,application/xhtml+xml,*/*;q=0.8")
                .statusCode() shouldBe 200
        }

        val refused = get(baseUrl, "/widget", accept = "application/xml")
        withClue("nothing this endpoint sends is anything this caller reads") {
            refused.statusCode() shouldBe 406
        }
        withClue("the 406 says what was on offer, which is the only way to find out") {
            (refused.body().contains("application/json")) shouldBe true
        }

        withClue("a 204 has no representation, so an Accept has nothing to refuse") {
            get(baseUrl, "/nothing", accept = "application/xml").statusCode() shouldBe 204
        }

        withClue("a legal status nobody registered is the status that goes out") {
            get(baseUrl, "/odd", accept = null).statusCode() shouldBe 419
        }

        probeNegotiation(baseUrl)
    }

    /**
     * The same handler, the same value, and two wire shapes — pinned as bytes,
     * because what a negotiated response promises is exactly what goes out.
     */
    private fun probeNegotiation(baseUrl: String) {
        val asJson = get(baseUrl, "/export", accept = "application/json")
        withClue("a caller asking for JSON gets the JSON rendering") {
            asJson.statusCode() shouldBe 200
            asJson.body() shouldBe """{"id":7}"""
            contentTypeOf(asJson) shouldStartWith "application/json"
        }

        val asCsv = get(baseUrl, "/export", accept = "text/csv")
        withClue("and the same value goes out as CSV for the caller that asked for that") {
            asCsv.statusCode() shouldBe 200
            asCsv.body() shouldBe "id\n7\n"
            contentTypeOf(asCsv) shouldStartWith "text/csv"
        }

        withClue("no Accept at all takes the first alternative in declaration order") {
            val silent = get(baseUrl, "/export", accept = null)
            silent.body() shouldBe """{"id":7}"""
            contentTypeOf(silent) shouldStartWith "application/json"
        }

        withClue("a q value is a preference, and the preferred rendering is the one that goes out") {
            get(baseUrl, "/export", accept = "application/json;q=0.2, text/csv;q=0.9").body() shouldBe "id\n7\n"
            get(baseUrl, "/export", accept = "application/json;q=0.9, text/csv;q=0.2")
                .body() shouldBe """{"id":7}"""
        }

        withClue("a named type beats the wildcard beside it, whatever order they were written in") {
            get(baseUrl, "/export", accept = "*/*;q=0.1, text/csv").body() shouldBe "id\n7\n"
        }

        val refused = get(baseUrl, "/export", accept = "application/xml")
        withClue("and a caller that will take neither is refused before the handler runs") {
            refused.statusCode() shouldBe 406
            refused.body() shouldContain "text/csv"
        }
    }

    private fun contentTypeOf(response: HttpResponse<String>): String =
        response.headers().firstValue("Content-Type").orElse("")

    @Test
    fun `pekko negotiates, and sends an unregistered status`() {
        val server = api(
            listOf(
                widget handledNowOnPekko { Widget(1) },
                odd handledNowOnPekko { Widget(2) },
                nothing handledNowOnPekko { },
                export handledNowOnPekko { Widget(7) },
            ),
        ).startOnPekko(port = 0, systemName = "negotiation")

        try {
            probe(server.baseUrl)
        } finally {
            server.stop()
        }
    }
}
