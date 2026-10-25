package io.github.matthewjones372.pelican.uring.served

import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.uring.RequiresUring
import io.github.matthewjones372.pelican.uring.handledBy
import io.github.matthewjones372.pelican.uring.handledNow
import io.github.matthewjones372.pelican.uring.handledWith
import io.github.matthewjones372.pelican.uring.start
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
import io.github.matthewjones372.pelican.jetty.handledBy as jettyHandledBy
import io.github.matthewjones372.pelican.jetty.handledNow as jettyHandledNow
import io.github.matthewjones372.pelican.jetty.handledWith as jettyHandledWith
import io.github.matthewjones372.pelican.jetty.start as jettyStart

data class Greeting(val text: String)

/** One API on both servers, asked the same questions over a socket. */
@RequiresUring
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

        private val uring = served(
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
            uring.stop()
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
    fun `uring answers as jetty does`(method: String, path: String) {
        withClue("$method $path") {
            ask(uring.baseUrl, method, path) shouldBe ask(jetty.baseUrl, method, path)
        }
    }

    @Test
    fun `a get is answered with the handler's value`() {
        ask(uring.baseUrl, "GET", "/hello/ada") shouldBe
            Seen(200, "application/json", null, """{"text":"Hello, ada!"}""")
    }

    @Test
    fun `a path nobody described is a 404`() {
        ask(uring.baseUrl, "GET", "/nobody/described/this").status shouldBe 404
    }

    @Test
    fun `a verb nobody declared is a 405 naming the ones that are`() {
        val seen = ask(uring.baseUrl, "PUT", "/hello/ada")

        seen.status shouldBe 405
        seen.allow shouldBe "GET, DELETE, POST"
    }
}
