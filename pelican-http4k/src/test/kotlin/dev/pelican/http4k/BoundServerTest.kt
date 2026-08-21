package dev.pelican.http4k

import dev.pelican.Api
import dev.pelican.jackson.JacksonCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
 * What the in-memory tests cannot see is everything below the handler: chunked
 * framing on the wire, the status line, whether a streamed response is actually
 * sent without a content length. This binds `SunHttp` — which ships inside
 * http4k-core, so a Pelican service on http4k needs no further dependency — on
 * a port the OS picks.
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
    fun `a declared failure keeps its status over the wire`() {
        get("/items/2").statusCode() shouldBe 404
    }

    @Test
    fun `a streamed response is chunked, with no content length`() {
        val res = get("/items/stream?limit=3")

        res.statusCode() shouldBe 200
        withClue("a streamed body must not be given a length, or the server would have to buffer it") {
            res.headers().firstValue("transfer-encoding").orElse(null) shouldBe "chunked"
        }
        res.headers().firstValue("content-length").isEmpty shouldBe true
        res.body().lines().count { it.isNotBlank() } shouldBe 3
    }

    @Test
    fun `a request body is read from the socket and echoed back`() {
        val res = client.send(
            HttpRequest.newBuilder(URI.create(server.baseUrl + "/echo"))
                .POST(HttpRequest.BodyPublishers.ofString("over the wire"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        res.body() shouldBe "over the wire"
    }

    @Test
    fun `an api with no endpoints is a startup failure, not a mystery 404`() {
        val empty = Api(endpoints = emptyList(), codecs = JacksonCodecs)
        val failure = runCatching { empty.toHttpHandler() }.exceptionOrNull()
        withClue("expected a startup failure, got $failure") { failure.shouldBeInstanceOf<IllegalArgumentException>() }
    }
}
