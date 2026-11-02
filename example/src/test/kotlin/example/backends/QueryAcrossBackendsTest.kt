package example.backends

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.github.matthewjones372.pelican.gannet.handledNow as handledOnGannet
import io.github.matthewjones372.pelican.gannet.start as startOnGannet
import io.github.matthewjones372.pelican.jetty.handledNow as handledOnJetty
import io.github.matthewjones372.pelican.jetty.start as startOnJetty
import io.github.matthewjones372.pelican.pekko.handledNow as handledOnPekko
import io.github.matthewjones372.pelican.pekko.start as startOnPekko

/** RFC 10008 `QUERY`, which no backend's router was written for, answered the same way by all three. */
class QueryAcrossBackendsTest {

    data class Filter(val status: String)

    data class Found(val status: String)

    class Served(val name: String, val baseUrl: String, val stop: () -> Unit) {
        override fun toString() = name
    }

    companion object {
        private val search = endpoint(jsonBody<Filter>()) {
            query("orders" / "search")
            json<Found>()
        }

        private val client: HttpClient = HttpClient.newHttpClient()

        private val served: List<Served> = listOf(
            api(listOf(search handledOnPekko { Found(it.status) }), JacksonCodecs).startOnPekko(port = 0)
                .let { Served("pekko", it.baseUrl) { it.stop() } },
            api(listOf(search handledOnJetty { Found(it.status) }), JacksonCodecs).startOnJetty(port = 0)
                .let { Served("jetty", it.baseUrl) { it.stop() } },
            api(listOf(search handledOnGannet { Found(it.status) }), JacksonCodecs).startOnGannet(port = 0)
                .let { Served("gannet", it.baseUrl) { it.stop() } },
        )

        @JvmStatic
        fun backends(): List<Served> = served

        @JvmStatic
        @AfterAll
        fun stopAll() {
            client.close()
            served.forEach { it.stop() }
        }
    }

    private fun ask(on: Served, contentType: String?, body: String): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(on.baseUrl + "/orders/search"))
            .method("QUERY", HttpRequest.BodyPublishers.ofString(body))
        contentType?.let { builder.header("Content-Type", it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a QUERY with a JSON body is answered with the typed value`(on: Served) {
        val res = ask(on, "application/json", """{"status":"open"}""")

        withClue(on.name) {
            res.statusCode() shouldBe 200
            res.body() shouldContain "\"status\":\"open\""
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a QUERY with no Content-Type is refused before the handler`(on: Served) {
        // Pekko's entity reports application/octet-stream where the header is absent, so it cannot tell the two
        // apart and answers 415; the others see no header and answer 400. Either way the handler never runs.
        val expected = if (on.name == "pekko") 415 else 400

        withClue(on.name) { ask(on, null, """{"status":"open"}""").statusCode() shouldBe expected }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a QUERY of a type the endpoint does not read is a 415`(on: Served) {
        withClue(on.name) { ask(on, "text/plain", "status=open").statusCode() shouldBe 415 }
    }
}
