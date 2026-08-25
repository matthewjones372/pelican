package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.github.matthewjones372.pelican.http4k.handledNow as handledNowOnHttp4k
import io.github.matthewjones372.pelican.http4k.start as startOnHttp4k
import io.github.matthewjones372.pelican.ktor.handledNow as handledNowOnKtor
import io.github.matthewjones372.pelican.ktor.start as startOnKtor
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

    private fun api(routes: List<ServerEndpoint>) = Api(endpoints = routes, codecs = JacksonCodecs)

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
    }

    @Test
    fun `pekko negotiates, and sends an unregistered status`() {
        val server = api(
            listOf(
                widget handledNowOnPekko { Widget(1) },
                odd handledNowOnPekko { Widget(2) },
                nothing handledNowOnPekko { },
            ),
        ).startOnPekko(port = 0, systemName = "negotiation")

        try {
            probe(server.baseUrl)
        } finally {
            server.stop().toCompletableFuture().join()
        }
    }

    @Test
    fun `http4k does the same`() {
        val server = api(
            listOf(
                widget handledNowOnHttp4k { Widget(1) },
                odd handledNowOnHttp4k { Widget(2) },
                nothing handledNowOnHttp4k { },
            ),
        ).startOnHttp4k(port = 0)

        try {
            probe(server.baseUrl)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `and so does ktor`() {
        val server = api(
            listOf(
                widget handledNowOnKtor { Widget(1) },
                odd handledNowOnKtor { Widget(2) },
                nothing handledNowOnKtor { },
            ),
        ).startOnKtor(port = 0)

        try {
            probe(server.baseUrl)
        } finally {
            server.stop()
        }
    }
}
