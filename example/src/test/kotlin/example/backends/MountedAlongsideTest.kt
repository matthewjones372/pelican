package example.backends

import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.pekko.http.javadsl.server.Directives
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.github.matthewjones372.pelican.pekko.handledNow as handledNowOnPekko
import io.github.matthewjones372.pelican.pekko.start as startOnPekko
import io.github.matthewjones372.pelican.pekko.toRoute as toPekkoRoute

class MountedAlongsideTest {

    data class Widget(val id: Long)

    private val widget = endpoint { get("widget"); json<Widget>() }

    private fun api(route: ServerEndpoint) = api(endpoints = listOf(route), codecs = JacksonCodecs)

    private fun get(baseUrl: String, path: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("$baseUrl$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun bothAnswer(baseUrl: String) {
        withClue("the hand-written route still answers") {
            get(baseUrl, "/health").body() shouldBe "ok"
        }
        withClue("the described endpoint answers beside it") {
            get(baseUrl, "/widget").body() shouldBe """{"id":1}"""
        }
    }

    @Test
    fun `pekko concatenates a described route with a hand-written one`() {
        val handWritten = Directives.path("health") {
            Directives.get { Directives.complete("ok") }
        }

        val server = api(widget handledNowOnPekko { Widget(1) })
            .startOnPekko(port = 0, systemName = "mounted-alongside") { system ->
                Directives.concat(handWritten, toPekkoRoute(system))
            }

        try {
            bothAnswer(server.baseUrl)
        } finally {
            server.stop()
        }
    }
}
