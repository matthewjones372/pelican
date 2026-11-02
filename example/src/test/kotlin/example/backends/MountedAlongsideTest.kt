package example.backends

import dev.surefold.pelican.ServerEndpoint
import dev.surefold.pelican.api
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.gannet.GannetHandler
import dev.surefold.pelican.gannet.RequiresGannet
import dev.surefold.pelican.gannet.then
import dev.surefold.pelican.jackson.JacksonCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.pekko.http.javadsl.server.Directives
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import dev.surefold.pelican.gannet.handledNow as handledNowOnGannet
import dev.surefold.pelican.gannet.start as startOnGannet
import dev.surefold.pelican.gannet.toHandler as toGannetHandler
import dev.surefold.pelican.jetty.handledNow as handledNowOnJetty
import dev.surefold.pelican.jetty.start as startOnJetty
import dev.surefold.pelican.jetty.toHandler as toJettyHandler
import dev.surefold.pelican.pekko.handledNow as handledNowOnPekko
import dev.surefold.pelican.pekko.start as startOnPekko
import dev.surefold.pelican.pekko.toRoute as toPekkoRoute

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

    @Test
    fun `jetty sequences a described handler with a hand-written one`() {
        val handWritten = object : Handler.Abstract.NonBlocking() {
            override fun handle(request: Request, response: Response, callback: Callback): Boolean {
                if (Request.getPathInContext(request) != "/health") return false
                Content.Sink.write(response, true, "ok", callback)
                return true
            }
        }

        val server = api(widget handledNowOnJetty { Widget(1) })
            .startOnJetty(port = 0) { Handler.Sequence(handWritten, toJettyHandler()) }

        try {
            bothAnswer(server.baseUrl)
        } finally {
            server.stop()
        }
    }

    @Test
    @RequiresGannet
    fun `gannet chains a described handler with a hand-written one`() {
        val handWritten = GannetHandler { exchange ->
            if (exchange.request.path != "/health") return@GannetHandler false
            exchange.respond(200, body = "ok".toByteArray())
            true
        }

        val server = api(widget handledNowOnGannet { Widget(1) })
            .startOnGannet(port = 0) { handWritten then toGannetHandler() }

        try {
            bothAnswer(server.baseUrl)
        } finally {
            server.stop()
        }
    }
}
