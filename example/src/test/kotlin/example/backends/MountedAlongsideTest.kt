package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.apache.pekko.http.javadsl.server.Directives
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.github.matthewjones372.pelican.http4k.handledNow as handledNowOnHttp4k
import io.github.matthewjones372.pelican.http4k.start as startOnHttp4k
import io.github.matthewjones372.pelican.http4k.toHttpHandler as toHttp4kHandler
import io.github.matthewjones372.pelican.ktor.handledNow as handledNowOnKtor
import io.github.matthewjones372.pelican.ktor.pelican as ktorPelican
import io.github.matthewjones372.pelican.ktor.start as startOnKtor
import io.github.matthewjones372.pelican.pekko.handledNow as handledNowOnPekko
import io.github.matthewjones372.pelican.pekko.start as startOnPekko
import io.github.matthewjones372.pelican.pekko.toRoute as toPekkoRoute

class MountedAlongsideTest {

    data class Widget(val id: Long)

    private val widget = endpoint { get("widget"); json<Widget>() }

    private fun api(route: ServerEndpoint) = Api(endpoints = listOf(route), codecs = JacksonCodecs)

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
            server.stop().toCompletableFuture().join()
        }
    }

    @Test
    fun `http4k combines the handler with routes of its own`() {
        val handWritten = "/health" bind Method.GET to { Response(Status.OK).body("ok") }

        val server = api(widget handledNowOnHttp4k { Widget(1) })
            .startOnHttp4k(port = 0) { routes(handWritten, toHttp4kHandler()) }

        try {
            bothAnswer(server.baseUrl)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `ktor installs them in an existing routing block`() {
        val server = api(widget handledNowOnKtor { Widget(1) })
            .startOnKtor(port = 0) { bound ->
                routing {
                    get("/health") { call.respondText("ok") }
                    ktorPelican(bound)
                }
            }

        try {
            bothAnswer(server.baseUrl)
        } finally {
            server.stop()
        }
    }
}
