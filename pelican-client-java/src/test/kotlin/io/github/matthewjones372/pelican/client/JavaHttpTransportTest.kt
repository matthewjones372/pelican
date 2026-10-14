package io.github.matthewjones372.pelican.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Method
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The adapter over a socket, served by the JDK's own `HttpServer`. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JavaHttpTransportTest {

    private val seen = ConcurrentHashMap<String, List<String>>()

    /** Released by the test that wants a second chunk written, once it has read the first. */
    private val drip = CountDownLatch(1)

    /** Answers `/slow` late without holding the server's only dispatch thread while it waits. */
    private val later = Executors.newSingleThreadScheduledExecutor()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
        createContext("/echo") { exchange -> echo(exchange) }
        createContext("/slow") { exchange ->
            later.schedule({ respond(exchange, "late") }, SLOW_MILLIS, TimeUnit.MILLISECONDS)
        }
        createContext("/drip") { exchange ->
            exchange.sendResponseHeaders(OK, 0)
            exchange.responseBody.use { out ->
                out.write("one".toByteArray())
                out.flush()
                drip.await(GATE_SECONDS, TimeUnit.SECONDS)
                out.write("two".toByteArray())
            }
        }
        executor = Executors.newCachedThreadPool()
        start()
    }

    private val base = "http://localhost:${server.address.port}"
    private val transport = JavaHttpTransport()

    @AfterAll
    fun tearDown() {
        server.stop(0)
        later.shutdownNow()
    }

    private fun echo(exchange: HttpExchange) {
        seen["method"] = listOf(exchange.requestMethod)
        seen["uri"] = listOf(exchange.requestURI.toString())
        listOf("X-Trace-Id", "X-Multi", "Content-Length", "Transfer-Encoding").forEach {
            seen[it] = exchange.requestHeaders[it].orEmpty().toList()
        }
        seen["body"] = listOf(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
        exchange.responseHeaders.add("X-Answer", "42")
        respond(exchange, "ok")
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(OK, bytes.size.toLong())
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

        response.status shouldBe OK
        response.text() shouldBe "ok"
        seen["method"] shouldBe listOf("POST")
        seen["uri"] shouldBe listOf("/echo?limit=3")
        seen["X-Trace-Id"] shouldBe listOf("abc")
        seen["body"] shouldBe listOf("""{"item":"anvil"}""")
    }

    @Test
    fun `a streaming body is sent from the stream it was given, chunked`() {
        send(
            ClientRequest(
                method = Method.POST,
                url = "$base/echo",
                body = ClientRequest.Body.Streaming { ByteArrayInputStream("streamed".toByteArray()) },
            ),
        )

        seen["body"] shouldBe listOf("streamed")
        seen["Transfer-Encoding"] shouldBe listOf("chunked")
    }

    @Test
    fun `a streaming body whose length the caller knows is sent sized, not chunked`() {
        send(
            ClientRequest(
                method = Method.POST,
                url = "$base/echo",
                headers = listOf("Content-Length" to "8"),
                body = ClientRequest.Body.Streaming { ByteArrayInputStream("measured".toByteArray()) },
            ),
        )

        seen["Content-Length"] shouldContainExactly listOf("8")
        seen["Transfer-Encoding"] shouldBe emptyList()
        seen["body"] shouldBe listOf("measured")
    }

    @Test
    fun `a header the caller wrote twice arrives twice`() {
        send(ClientRequest(Method.GET, "$base/echo", headers = listOf("X-Multi" to "one", "X-Multi" to "two")))

        seen["X-Multi"] shouldContainExactly listOf("one", "two")
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
    fun `a per-request timeout is the transport's to honour`() {
        val failure = shouldThrow<CompletionException> {
            send(ClientRequest(Method.GET, "$base/slow").withTimeout(Duration.ofMillis(TIMEOUT_MILLIS)))
        }

        // `is`, not an exact type: a budget this short can be spent before the
        // connection is up, and the JDK then throws a subclass.
        failure.cause.shouldBeInstanceOf<HttpTimeoutException>()
        failure.message.orEmpty() shouldContain "timed out"
    }

    /** The server holds the second chunk until well past the deadline. */
    @Test
    fun `the deadline lets a body it raced keep streaming`() {
        val response = send(ClientRequest(Method.GET, "$base/drip").withTimeout(Duration.ofMillis(TIMEOUT_MILLIS)))

        response.body.use { body ->
            body.readNBytes(3).toString(Charsets.UTF_8) shouldBe "one"
            Thread.sleep(TIMEOUT_MILLIS * 2)
            drip.countDown()
            body.readBytes().toString(Charsets.UTF_8) shouldBe "two"
        }
    }

    @Test
    fun `a refused connection crosses as an IOException, which a retry policy retries by default`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        val failure =
            shouldThrow<CompletionException> { send(ClientRequest(Method.GET, "http://localhost:$closedPort/")) }

        failure.cause.shouldBeInstanceOf<IOException>()
    }

    @Test
    fun `a call cancelled before the head arrives leaves the transport usable`() {
        val abandoned = transport.send(ClientRequest(Method.GET, "$base/slow")).toCompletableFuture()

        abandoned.cancel(true) shouldBe true

        send(ClientRequest(Method.GET, "$base/echo")).text() shouldBe "ok"
    }

    private companion object {
        const val OK = 200
        const val SLOW_MILLIS = 2_000L
        const val TIMEOUT_MILLIS = 200L
        const val GATE_SECONDS = 10L
    }
}
