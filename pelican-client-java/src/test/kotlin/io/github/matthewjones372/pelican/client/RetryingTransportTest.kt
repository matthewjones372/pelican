package io.github.matthewjones372.pelican.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.RetryPolicy
import io.github.matthewjones372.pelican.retrying
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * The retry decorator over a socket, rather than over a stubbed transport.
 *
 * `RetryTest` in core asserts what the policy decides; this asserts that the
 * decision reaches a server — that a second request is really sent, that it
 * carries the body the first one drained, and that a request nothing here
 * retries arrives exactly once.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetryingTransportTest {

    private val attempts = AtomicInteger()
    private val bodies = ConcurrentLinkedQueue<String>()

    /** Fails [failuresFirst] times with a 503 and answers after that. */
    private val failuresFirst = AtomicInteger(1)

    private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
        createContext("/flaky") { exchange -> flaky(exchange) }
        createContext("/refused") { exchange -> refuse(exchange) }
        start()
    }

    private val base = "http://localhost:${server.address.port}"

    /** No jitter and no waiting: the pause is asserted in core, not here. */
    private val impatient = RetryPolicy(jitter = 0.0, initialBackoff = Duration.ZERO, retryStreamedBodies = true)

    private val transport = JavaHttpTransport().retrying(impatient)

    @BeforeEach
    fun reset() {
        attempts.set(0)
        bodies.clear()
        failuresFirst.set(1)
    }

    @AfterAll
    fun tearDown() = server.stop(0)

    private fun flaky(exchange: HttpExchange) {
        bodies += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val number = attempts.incrementAndGet()
        if (number <= failuresFirst.get()) respond(exchange, 503, "not now") else respond(exchange, 200, "ok")
    }

    /** A status the policy does not retry, so that one request stays one request. */
    private fun refuse(exchange: HttpExchange) {
        attempts.incrementAndGet()
        respond(exchange, 400, "no")
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun send(request: ClientRequest) = transport.send(request).toCompletableFuture().join()

    @Test
    fun `a server that fails and then succeeds is called twice and answers once`() {
        val response = send(ClientRequest(Method.GET, "$base/flaky"))

        response.status shouldBe 200
        response.text() shouldBe "ok"
        attempts.get() shouldBe 2
    }

    @Test
    fun `a server that keeps failing hands back the last answer it gave`() {
        failuresFirst.set(Int.MAX_VALUE)

        val response = send(ClientRequest(Method.GET, "$base/flaky"))

        response.status shouldBe 503
        response.text() shouldBe "not now"
        attempts.get() shouldBe 3
    }

    @Test
    fun `a status the policy does not retry is asked for exactly once`() {
        send(ClientRequest(Method.GET, "$base/refused")).status shouldBe 400

        attempts.get() shouldBe 1
    }

    /**
     * The point of `Body.Streaming` carrying a function rather than a stream.
     * The first attempt drains what the function opened; the second has to be
     * given a new one, and the assertion is that the server read the same bytes
     * both times rather than an empty body the second.
     */
    @Test
    fun `a streamed body is opened again for the attempt that follows it`() {
        val response = send(
            ClientRequest(
                method = Method.PUT,
                url = "$base/flaky",
                body = ClientRequest.Body.Streaming { ByteArrayInputStream("anvil".toByteArray()) },
            ),
        )

        response.status shouldBe 200
        bodies.toList() shouldBe listOf("anvil", "anvil")
    }

    @Test
    fun `a text body survives the second attempt the same way`() {
        send(
            ClientRequest(
                method = Method.PUT,
                url = "$base/flaky",
                body = ClientRequest.Body.Text("""{"item":"anvil"}"""),
            ),
        ).status shouldBe 200

        bodies.toList() shouldBe listOf("""{"item":"anvil"}""", """{"item":"anvil"}""")
    }

    @Test
    fun `a connection that cannot be made is retried and then given up on`() {
        // Nothing is listening on this port, so every attempt fails the way a
        // dead node does: an IOException before any status exists.
        val nowhere = ClientRequest(Method.GET, "http://localhost:1/flaky")

        val failed = assertFailsWith<CompletionException> {
            transport.send(nowhere).toCompletableFuture().join()
        }

        failed.cause.shouldBeInstanceOf<IOException>()
    }
}
