package dev.pelican.ktor

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The same API over a real socket.
 *
 * What the test-engine tests cannot see is everything below the handler:
 * chunked framing on the wire, the status line, whether a streamed response is
 * actually sent without a content length. This binds CIO — which ships with
 * this module, so a Pelican service on Ktor needs no further dependency — on a
 * port the OS picks.
 */
class BoundServerTest {

    companion object {
        private lateinit var server: PelicanServer

        private val client: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        @JvmStatic
        @BeforeAll
        fun start() {
            server = testApi().start(port = 0)
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            server.stop()
        }
    }

    private fun get(path: String): HttpResponse<String> = client.send(
        HttpRequest.newBuilder(URI.create(server.baseUrl + path)).timeout(Duration.ofSeconds(10)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `a bound server answers on the port it reports`() {
        val res = get("/items/1")
        res.statusCode() shouldBe 200
        res.body() shouldBe """{"id":1,"name":"widget"}"""
        res.headers().firstValue("content-type").orElse(null) shouldBe "application/json"
    }

    @Test
    fun `a streamed response is chunked rather than measured`() {
        val res = get("/items/stream?limit=3")
        res.statusCode() shouldBe 200
        res.body().lines().count { it.isNotBlank() } shouldBe 3
        // No content length: the server cannot know one without buffering the
        // whole stream, which is the thing being avoided.
        withClue("a streamed response declared a length, so something buffered it") {
            res.headers().firstValue("content-length").isEmpty shouldBe true
        }
    }

    @Test
    fun `a request body reaches the handler over the wire`() {
        val res = client.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl + "/items"))
                .header("X-Api-Key", "let-me-in")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"name":"rope"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        res.statusCode() shouldBe 201
        res.body() shouldBe """{"id":7,"name":"rope"}"""
    }

    @Test
    fun `an uploaded body is echoed back as it arrives`() {
        val body = "the quick brown fox".repeat(500)
        val res = client.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl + "/echo"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        res.statusCode() shouldBe 200
        res.body() shouldBe body
    }

    @Test
    fun `a declared failure keeps its status over the wire`() {
        get("/items/2").statusCode() shouldBe 404
    }
}
