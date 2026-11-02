package dev.surefold.pelican.gannet.served

import dev.surefold.pelican.ServerEndpoint
import dev.surefold.pelican.api
import dev.surefold.pelican.div
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.gannet.RequiresGannet
import dev.surefold.pelican.gannet.handledBy
import dev.surefold.pelican.gannet.handledNow
import dev.surefold.pelican.gannet.handledWith
import dev.surefold.pelican.gannet.start
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import dev.surefold.pelican.jetty.handledBy as jettyHandledBy
import dev.surefold.pelican.jetty.handledNow as jettyHandledNow
import dev.surefold.pelican.jetty.handledWith as jettyHandledWith
import dev.surefold.pelican.jetty.start as jettyStart

data class Greeting(val text: String)

/** One API on both servers, asked the same questions over a socket. */
@RequiresGannet
class ServedAsJettyServesTest {

    /** What a caller can see of an answer, and so what the two servers are compared on. */
    data class Seen(val status: Int, val contentType: String?, val allow: String?, val body: String)

    companion object {
        private val who = pathParam<String>("who")

        private val greet = endpoint(who) {
            get("hello" / who)
            json<Greeting>()
        }

        private val forget = endpoint(who) {
            delete("hello" / who)
            empty()
        }

        private val motd = endpoint {
            get("motd")
            text()
        }

        private val outbox = endpoint {
            post("outbox")
            text(status = 202)
        }

        private fun served(endpoints: List<ServerEndpoint>) = api(endpoints, JacksonCodecs)

        private val gannet = served(
            listOf(
                greet handledNow { name -> Greeting("Hello, $name!") },
                forget handledWith { },
                motd handledNow { "Be excellent to each other." },
                outbox handledBy { CompletableFuture.completedFuture("queued") },
            ),
        ).start(port = 0)

        private val jetty = served(
            listOf(
                greet jettyHandledNow { name -> Greeting("Hello, $name!") },
                forget jettyHandledWith { },
                motd jettyHandledNow { "Be excellent to each other." },
                outbox jettyHandledBy { CompletableFuture.completedFuture("queued") },
            ),
        ).jettyStart(port = 0)

        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stopBoth() {
            client.close()
            gannet.stop()
            jetty.stop()
        }
    }

    private fun ask(baseUrl: String, method: String, path: String): Seen {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        val res = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Seen(
            res.statusCode(),
            res.headers().firstValue("Content-Type").orElse(null),
            res.headers().firstValue("Allow").orElse(null),
            res.body(),
        )
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "GET, /hello/ada",
        "GET, /motd",
        "DELETE, /hello/ada",
        "POST, /outbox",
        "GET, /nobody/described/this",
        "PUT, /hello/ada",
        "POST, /hello/ada",
    )
    fun `gannet answers as jetty does`(method: String, path: String) {
        withClue("$method $path") {
            ask(gannet.baseUrl, method, path) shouldBe ask(jetty.baseUrl, method, path)
        }
    }

    @Test
    fun `a get is answered with the handler's value`() {
        ask(gannet.baseUrl, "GET", "/hello/ada") shouldBe
            Seen(200, "application/json", null, """{"text":"Hello, ada!"}""")
    }

    @Test
    fun `a path nobody described is a 404`() {
        ask(gannet.baseUrl, "GET", "/nobody/described/this").status shouldBe 404
    }

    @Test
    fun `a verb nobody declared is a 405 naming the ones that are`() {
        val seen = ask(gannet.baseUrl, "PUT", "/hello/ada")

        seen.status shouldBe 405
        seen.allow shouldBe "GET, DELETE, POST"
    }
}
