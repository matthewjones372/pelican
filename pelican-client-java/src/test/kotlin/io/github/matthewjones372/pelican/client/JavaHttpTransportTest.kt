package io.github.matthewjones372.pelican.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Method
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletionException
import kotlin.test.assertFailsWith

/**
 * The adapter over a socket, served by the JDK's own `HttpServer` so that this
 * module's tests need no backend either.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JavaHttpTransportTest {

    private val seen = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
        createContext("/echo") { exchange -> echo(exchange) }
        createContext("/slow") { exchange ->
            Thread.sleep(SLOW_MILLIS)
            respond(exchange, "late")
        }
        start()
    }

    private val base = "http://localhost:${server.address.port}"
    private val transport = JavaHttpTransport()

    @AfterAll
    fun tearDown() = server.stop(0)

    private fun echo(exchange: HttpExchange) {
        seen["method"] = exchange.requestMethod
        seen["uri"] = exchange.requestURI.toString()
        seen["trace"] = exchange.requestHeaders.getFirst("X-Trace-Id").orEmpty()
        seen["body"] = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        exchange.responseHeaders.add("X-Answer", "42")
        respond(exchange, "ok")
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun send(request: ClientRequest) = transport.send(request).toCompletableFuture().join()

    @Test
    fun `the method, url, headers and text body all cross`() {
        val response = send(
            ClientRequest(
                method = Method.POST,
                url = "$base/echo?limit=3",
                headers = listOf("X-Trace-Id" to "abc", "Content-Type" to "application/json"),
                body = ClientRequest.Body.Text("""{"item":"anvil"}"""),
            ),
        )

        response.status shouldBe 200
        response.text() shouldBe "ok"
        seen["method"] shouldBe "POST"
        seen["uri"] shouldBe "/echo?limit=3"
        seen["trace"] shouldBe "abc"
        seen["body"] shouldBe """{"item":"anvil"}"""
    }

    @Test
    fun `a streaming body is sent from the stream it was given`() {
        val response = send(
            ClientRequest(
                method = Method.POST,
                url = "$base/echo",
                body = ClientRequest.Body.Streaming { ByteArrayInputStream("streamed".toByteArray()) },
            ),
        )

        response.status shouldBe 200
        seen["body"] shouldBe "streamed"
    }

    @Test
    fun `response headers survive the crossing`() {
        val response = send(ClientRequest(Method.GET, "$base/echo"))

        response.header("X-Answer") shouldBe "42"
        response.header("x-answer") shouldBe "42"
        response.header("X-Absent") shouldBe null
        response.headers.map { it.first.lowercase() } shouldContainAll listOf("x-answer", "content-length")
    }

    @Test
    fun `the body arrives unread, and is the caller's to close`() {
        val response = send(ClientRequest(Method.GET, "$base/echo"))

        response.body.use { it.readBytes().toString(Charsets.UTF_8) } shouldBe "ok"
    }

    @Test
    fun `a per-request timeout is the transport's to honour`() {
        val failure = assertFailsWith<CompletionException> {
            send(ClientRequest(Method.GET, "$base/slow", timeout = Duration.ofMillis(TIMEOUT_MILLIS)))
        }

        failure.cause!!::class.simpleName shouldBe HttpTimeoutException::class.simpleName
        failure.message.orEmpty() shouldContain "timed out"
    }

    private companion object {
        const val SLOW_MILLIS = 2_000L
        const val TIMEOUT_MILLIS = 200L
    }
}
