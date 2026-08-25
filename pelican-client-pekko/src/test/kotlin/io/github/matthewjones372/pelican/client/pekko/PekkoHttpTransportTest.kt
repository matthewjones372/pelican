package io.github.matthewjones372.pelican.client.pekko

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Method
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertFailsWith

/**
 * The adapter over a socket, served by the JDK's own `HttpServer` rather than
 * by Pekko: a client tested against the server half of the same library proves
 * less about what goes on the wire than one tested against a stranger.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PekkoHttpTransportTest {

    private val seen = ConcurrentHashMap<String, String>()
    private val contentTypes = ConcurrentHashMap<String, List<String>>()

    /** Released by the test that wants a second chunk written, once it has read the first. */
    private val drip = CountDownLatch(1)

    private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
        createContext("/echo") { exchange -> echo(exchange) }
        createContext("/empty") { exchange -> exchange.sendResponseHeaders(NO_CONTENT, -1); exchange.close() }
        createContext("/slow") { exchange ->
            Thread.sleep(SLOW_MILLIS)
            respond(exchange, "late")
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
        createContext("/bulk") { exchange -> bulk(exchange) }
        start()
    }

    private val base = "http://localhost:${server.address.port}"
    private val transport = PekkoHttpTransport()

    /**
     * A system of the caller's own, configured so that a request sent on it is
     * distinguishable from one sent on the shared one.
     */
    private val ownSystem: ActorSystem<Void> = ActorSystem.create(
        Behaviors.empty(),
        "handed-over",
        ConfigFactory
            .parseString(
                """
                pekko.http.client.user-agent-header = "handed-over/1.0"
                pekko.http.client.parsing.max-content-length = 1k
                """.trimIndent(),
            )
            .withFallback(ConfigFactory.load()),
    )

    @AfterAll
    fun tearDown() {
        server.stop(0)
        ownSystem.terminate()
    }

    private fun echo(exchange: HttpExchange) {
        seen["method"] = exchange.requestMethod
        seen["uri"] = exchange.requestURI.toString()
        seen["trace"] = exchange.requestHeaders.getFirst("X-Trace-Id").orEmpty()
        seen["length"] = exchange.requestHeaders.getFirst("Content-Length").orEmpty()
        seen["encoding"] = exchange.requestHeaders.getFirst("Transfer-Encoding").orEmpty()
        seen["agent"] = exchange.requestHeaders.getFirst("User-Agent").orEmpty()
        contentTypes["sent"] = exchange.requestHeaders["Content-Type"].orEmpty().toList()
        seen["body"] = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        exchange.responseHeaders.add("X-Answer", "42")
        respond(exchange, "ok")
    }

    private fun bulk(exchange: HttpExchange) {
        val wanted = exchange.requestURI.query.removePrefix("bytes=").toInt()
        val chunk = ByteArray(CHUNK) { 'x'.code.toByte() }
        exchange.sendResponseHeaders(OK, 0)
        exchange.responseBody.use { out ->
            repeat(wanted / CHUNK) {
                out.write(chunk)
                out.flush()
            }
        }
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/plain")
        exchange.sendResponseHeaders(OK, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun send(request: ClientRequest, on: PekkoHttpTransport = transport) =
        on.send(request).toCompletableFuture().join()

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

    /**
     * Pekko keeps `Content-Type` on the entity rather than in the header list,
     * so a naive crossing either drops the caller's or sends it twice.
     */
    @Test
    fun `a declared content type reaches the wire exactly once`() {
        send(
            ClientRequest(
                method = Method.POST,
                url = "$base/echo",
                headers = listOf("Content-Type" to "application/json"),
                body = ClientRequest.Body.Text("{}"),
            ),
        )

        contentTypes["sent"] shouldContainExactly listOf("application/json")
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
    fun `a streaming body whose length the caller knows is sent sized, not chunked`() {
        send(
            ClientRequest(
                method = Method.POST,
                url = "$base/echo",
                headers = listOf("Content-Length" to "8"),
                body = ClientRequest.Body.Streaming { ByteArrayInputStream("measured".toByteArray()) },
            ),
        )

        seen["length"] shouldBe "8"
        seen["encoding"] shouldBe ""
        seen["body"] shouldBe "measured"
    }

    @Test
    fun `response headers survive the crossing, the entity's two among them`() {
        val response = send(ClientRequest(Method.GET, "$base/echo"))

        response.header("X-Answer") shouldBe "42"
        response.header("x-answer") shouldBe "42"
        response.header("X-Absent") shouldBe null
        response.header("Content-Type") shouldBe "text/plain"
        response.headers.map { it.first.lowercase() } shouldContainAll listOf("x-answer", "content-length")
        withClue("Pekko strips these from the header list; putting them back must not double them") {
            response.headers.count { it.first.equals("Content-Type", ignoreCase = true) } shouldBe 1
            response.headers.count { it.first.equals("Content-Length", ignoreCase = true) } shouldBe 1
        }
    }

    @Test
    fun `a status that allows no entity is given no content headers of its own`() {
        val response = send(ClientRequest(Method.GET, "$base/empty"))

        response.status shouldBe NO_CONTENT
        response.header("Content-Type") shouldBe null
        response.header("Content-Length") shouldBe null
    }

    @Test
    fun `the body arrives unread, and is read as the server writes it`() {
        val response = send(ClientRequest(Method.GET, "$base/drip"))

        response.body.use { body ->
            // The server holds the second chunk until this test releases it, so
            // a bridge that waited for the whole entity would sit here until the
            // gate timed out and still read "one" afterwards. The clock is what
            // tells those two apart.
            val started = System.nanoTime()
            val first = body.readNBytes(3).toString(Charsets.UTF_8)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            first shouldBe "one"
            withClue("the first chunk took ${elapsedMs}ms — that looks buffered") {
                elapsedMs shouldBeLessThan PROMPT_MILLIS
            }

            drip.countDown()
            body.readBytes().toString(Charsets.UTF_8) shouldBe "two"
        }
    }

    /**
     * The bridge hands bytes over through a bounded queue. A reader slower than
     * the socket must backpressure it rather than fill memory or wedge.
     */
    @Test
    fun `a slow reader backpressures rather than deadlocking`() {
        val response = send(ClientRequest(Method.GET, "$base/bulk?bytes=$BULK"))

        val read = response.body.use { body -> drainSlowly(body) }

        read shouldBe BULK
    }

    private fun drainSlowly(body: InputStream): Int {
        val buffer = ByteArray(CHUNK)
        var total = 0
        var reads = 0
        while (true) {
            val n = body.read(buffer)
            if (n < 0) return total
            total += n
            if (reads++ % PAUSE_EVERY == 0) Thread.sleep(1)
        }
    }

    @Test
    fun `a per-request timeout is the transport's to honour`() {
        val failure = assertFailsWith<CompletionException> {
            send(ClientRequest(Method.GET, "$base/slow", timeout = Duration.ofMillis(TIMEOUT_MILLIS)))
        }

        failure.cause!!::class shouldBe TimeoutException::class
        failure.cause!!.message.orEmpty() shouldContain "GET $base/slow timed out"
    }

    @Test
    fun `a response closed unread leaves the transport usable`() {
        send(ClientRequest(Method.GET, "$base/bulk?bytes=$BULK")).body.close()

        send(ClientRequest(Method.GET, "$base/echo")).text() shouldBe "ok"
    }

    /**
     * Not just that a handed-over system is accepted: that the request went out
     * on it, which the user-agent that system configures is the evidence for.
     */
    @Test
    fun `a system handed over is the one the request is sent on`() {
        send(ClientRequest(Method.GET, "$base/echo"), on = PekkoHttpTransport(ownSystem))

        seen["agent"] shouldBe "handed-over/1.0"
    }

    /**
     * `max-content-length` bounds what a server accepts from a caller it does
     * not trust. A response this client asked for is not that, and a `bytes()`
     * body may be larger than the process.
     */
    @Test
    fun `a response larger than the system's content limit still crosses`() {
        val response = send(
            ClientRequest(Method.GET, "$base/bulk?bytes=$BULK"),
            on = PekkoHttpTransport(ownSystem),
        )

        response.body.use { it.readBytes().size } shouldBe BULK
    }

    @Test
    fun `the shared system does not hold a finished process open`() {
        send(ClientRequest(Method.GET, "$base/echo"))

        val live = Thread.getAllStackTraces().keys.filter { it.name.startsWith("pelican-client-") }
        withClue("a transport nobody was handed a close for must not keep the JVM alive") {
            live.count { !it.isDaemon } shouldBe 0
        }
        withClue("the shared system should have started, or this asserts nothing") {
            live.shouldNotBeEmpty()
        }
    }

    private companion object {
        const val OK = 200
        const val NO_CONTENT = 204
        const val SLOW_MILLIS = 2_000L
        const val TIMEOUT_MILLIS = 200L
        const val GATE_SECONDS = 10L
        const val PROMPT_MILLIS = 2_000L
        const val CHUNK = 8 * 1024
        const val BULK = 1024 * 1024
        const val PAUSE_EVERY = 16
    }
}
