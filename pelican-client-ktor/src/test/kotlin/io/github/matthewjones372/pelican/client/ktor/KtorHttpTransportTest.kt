package io.github.matthewjones372.pelican.client.ktor

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Method
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.pluginOrNull
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertFailsWith

/**
 * The adapter over a socket, served by the JDK's own `HttpServer` rather than
 * by Ktor: a client tested against the server half of the same library proves
 * less about what goes on the wire than one tested against a stranger.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KtorHttpTransportTest {

    private val seen = ConcurrentHashMap<String, String>()
    private val repeated = ConcurrentHashMap<String, List<String>>()

    /**
     * One gate per `/drip` request, named by the caller: the test that wants a
     * second chunk written is the one that opens it, and two tests dripping at
     * once must not open each other's.
     */
    private val gates = ConcurrentHashMap<String, CountDownLatch>()

    /** The pair the cancellation test uses: one to wait on the server, one to let it go. */
    private val arrived = CountDownLatch(1)
    private val release = CountDownLatch(1)

    private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
        // A handler that blocks must not stop the next request being served,
        // and a non-daemon pool here would fail the test below that counts
        // threads nobody closes.
        executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "test-server").apply { isDaemon = true }
        }
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
                gate(exchange.requestURI.query).await(GATE_SECONDS, TimeUnit.SECONDS)
                out.write("two".toByteArray())
            }
        }
        createContext("/held") { exchange ->
            arrived.countDown()
            release.await(GATE_SECONDS, TimeUnit.SECONDS)
            respond(exchange, "let go")
        }
        createContext("/bulk") { exchange -> bulk(exchange) }
        start()
    }

    private val base = "http://localhost:${server.address.port}"
    private val transport = KtorHttpTransport()

    /**
     * A client of the caller's own, configured so that a request sent on it is
     * distinguishable from one sent on the shared one, and carrying neither the
     * timeout plugin nor a wide connection pool — which is what two of the
     * tests below are about.
     */
    private val ownClient: HttpClient = HttpClient(CIO) {
        install(UserAgent) { agent = "handed-over/1.0" }
        engine { maxConnectionsCount = 1 }
    }

    /** What was running before this suite sent anything; see the last test. */
    private val threadsAtStart: Set<Thread> = Thread.getAllStackTraces().keys.toSet()

    @AfterAll
    fun tearDown() {
        release.countDown()
        gates.values.forEach { it.countDown() }
        server.stop(0)
        ownClient.close()
    }

    private fun gate(name: String): CountDownLatch = gates.computeIfAbsent(name) { CountDownLatch(1) }

    private fun echo(exchange: HttpExchange) {
        seen["method"] = exchange.requestMethod
        seen["uri"] = exchange.requestURI.toString()
        seen["trace"] = exchange.requestHeaders.getFirst("X-Trace-Id").orEmpty()
        seen["length"] = exchange.requestHeaders.getFirst("Content-Length").orEmpty()
        seen["encoding"] = exchange.requestHeaders.getFirst("Transfer-Encoding").orEmpty()
        seen["agent"] = exchange.requestHeaders.getFirst("User-Agent").orEmpty()
        repeated["type"] = exchange.requestHeaders["Content-Type"].orEmpty().toList()
        repeated["lengths"] = exchange.requestHeaders["Content-Length"].orEmpty().toList()
        repeated["tags"] = exchange.requestHeaders["X-Tag"].orEmpty().toList()
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

    private fun send(request: ClientRequest, on: KtorHttpTransport = transport) =
        on.send(request).toCompletableFuture().join()

    /**
     * For the tests that assert on what the server saw rather than on what came
     * back: the body is read and closed, because a response nobody finishes
     * with holds its connection — which the one-connection client below would
     * fail on, and rightly.
     */
    private fun call(request: ClientRequest, on: KtorHttpTransport = transport) {
        send(request, on).text()
    }

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
     * Ktor renders these two off the body rather than out of the header list,
     * and prefers the body's where both exist, so a naive crossing either drops
     * the caller's or sends it twice.
     */
    @Test
    fun `a declared content type and length reach the wire exactly once`() {
        call(
            ClientRequest(
                method = Method.POST,
                url = "$base/echo",
                headers = listOf("Content-Type" to "application/json", "Content-Length" to "2"),
                body = ClientRequest.Body.Text("{}"),
            ),
        )

        repeated["type"] shouldContainExactly listOf("application/json")
        repeated["lengths"] shouldContainExactly listOf("2")
    }

    /**
     * Not the JDK adapter's rendering, and worth a test so that the difference
     * is a decision rather than a discovery: Ktor's engines fold repeats of one
     * name into the comma-separated list RFC 9110 makes them equivalent to.
     */
    @Test
    fun `a header the caller wrote twice arrives as one name carrying both values`() {
        call(
            ClientRequest(
                method = Method.GET,
                url = "$base/echo",
                headers = listOf("X-Tag" to "one", "X-Tag" to "two"),
            ),
        )

        repeated["tags"] shouldContainExactly listOf("one,two")
    }

    @Test
    fun `a streaming body is sent from the stream it was given, chunked where its length is unknown`() {
        val response = send(
            ClientRequest(
                method = Method.POST,
                url = "$base/echo",
                body = ClientRequest.Body.Streaming { ByteArrayInputStream("streamed".toByteArray()) },
            ),
        )

        response.status shouldBe 200
        response.text() shouldBe "ok"
        seen["body"] shouldBe "streamed"
        seen["encoding"] shouldBe "chunked"
    }

    @Test
    fun `a streaming body whose length the caller knows is sent sized, not chunked`() {
        call(
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
    fun `response headers survive the crossing, the body's two among them`() {
        val response = send(ClientRequest(Method.GET, "$base/echo"))

        response.header("X-Answer") shouldBe "42"
        response.header("x-answer") shouldBe "42"
        response.header("X-Absent") shouldBe null
        response.header("Content-Type") shouldBe "text/plain"
        response.headers.map { it.first.lowercase() } shouldContainAll listOf("x-answer", "content-length")
        withClue("Ktor hands back what it received; adding either back from the body would double it") {
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
        val response = send(ClientRequest(Method.GET, "$base/drip?gate=read"))

        response.body.use { body ->
            // The server holds the second chunk until this test releases it, so
            // a bridge that waited for the whole body would sit here until the
            // gate timed out and still read "one" afterwards. The clock is what
            // tells those two apart.
            val started = System.nanoTime()
            val first = body.readNBytes(3).toString(Charsets.UTF_8)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            first shouldBe "one"
            withClue("the first chunk took ${elapsedMs}ms — that looks buffered") {
                elapsedMs shouldBeLessThan PROMPT_MILLIS
            }

            gate("gate=read").countDown()
            body.readBytes().toString(Charsets.UTF_8) shouldBe "two"
        }
    }

    /**
     * The bridge hands bytes over through a channel the reader drains. A reader
     * slower than the socket must backpressure it rather than fill memory or
     * wedge.
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

    /**
     * Ktor's client bounds a response by time rather than by size — there is no
     * `max-content-length` to lift — so what a `bytes()` body meets is the
     * engine's deadline, not a byte cap. Several megabytes arriving whole is
     * the assertion that there is no cap; the deadline is the test after this
     * one.
     */
    @Test
    fun `a response far larger than any buffer crosses whole`() {
        val response = send(ClientRequest(Method.GET, "$base/bulk?bytes=$LARGE"), on = KtorHttpTransport(ownClient))

        response.body.use { it.readBytes().size } shouldBe LARGE
    }

    /**
     * CIO's own `requestTimeout` — fifteen seconds by default — cancels the
     * whole exchange, a half-read body included. The client this module keeps
     * lifts it, because a caller who set no timeout did not ask for one and an
     * `sse` response that stays open longer is the endpoint working. Turned
     * down to a fraction of a second here, it is the same mechanism in miniature.
     */
    @Test
    fun `an engine deadline cuts a streamed body, and the module's own client sets none`() {
        val cut = HttpClient(CIO) { engine { requestTimeout = TIMEOUT_MILLIS } }.use { capped ->
            val response = send(ClientRequest(Method.GET, "$base/drip?gate=capped"), on = KtorHttpTransport(capped))
            runCatching { response.body.use { it.readBytes().toString(Charsets.UTF_8) } }
        }

        withClue("the engine's own deadline should have ended this body, and did not: $cut") {
            cut.getOrNull() shouldBe null
        }

        // The same response, unhurried, on the client the module keeps: the
        // second chunk is written only once this test asks for it.
        send(ClientRequest(Method.GET, "$base/drip?gate=lifted")).body.use { body ->
            body.readNBytes(3).toString(Charsets.UTF_8) shouldBe "one"
            Thread.sleep(TIMEOUT_MILLIS * SLACK)
            gate("gate=lifted").countDown()
            body.readBytes().toString(Charsets.UTF_8) shouldBe "two"
        }
    }

    @Test
    fun `a per-request timeout is the transport's to honour`() {
        val started = System.nanoTime()
        val failure = assertFailsWith<CompletionException> {
            send(ClientRequest(Method.GET, "$base/slow", timeout = Duration.ofMillis(TIMEOUT_MILLIS)))
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        failure.cause!!::class shouldBe HttpRequestTimeoutException::class
        failure.cause!!.message.orEmpty() shouldContain "$base/slow"
        elapsedMs shouldBeLessThan SLOW_MILLIS
    }

    /**
     * A deadline on a request is a capability, and only the `HttpTimeout`
     * plugin turns it into a cancellation. A client handed over without that
     * plugin would drop the timeout in silence, so the adapter imposes it
     * itself and raises the same exception Ktor would have.
     */
    @Test
    fun `a per-request timeout still holds on a client with no timeout plugin`() {
        HttpClient(CIO).use { plain ->
            withClue("this client was meant to carry no timeout plugin") {
                plain.pluginOrNull(HttpTimeout) shouldBe null
            }

            val started = System.nanoTime()
            val failure = assertFailsWith<CompletionException> {
                send(
                    ClientRequest(Method.GET, "$base/slow", timeout = Duration.ofMillis(TIMEOUT_MILLIS)),
                    on = KtorHttpTransport(plain),
                )
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000

            failure.cause!!::class shouldBe HttpRequestTimeoutException::class
            withClue("the deadline was dropped: the call ran to the server's own ${SLOW_MILLIS}ms") {
                elapsedMs shouldBeLessThan SLOW_MILLIS
            }
        }
    }

    @Test
    fun `a response closed unread leaves the transport usable`() {
        send(ClientRequest(Method.GET, "$base/bulk?bytes=$BULK")).body.close()

        send(ClientRequest(Method.GET, "$base/echo")).text() shouldBe "ok"
    }

    /**
     * Not just that a handed-over client is accepted: that the request went out
     * on it, which the user agent that client installs is the evidence for.
     */
    @Test
    fun `a client handed over is the one the request is sent on`() {
        call(ClientRequest(Method.GET, "$base/echo"), on = KtorHttpTransport(ownClient))

        seen["agent"] shouldBe "handed-over/1.0"
    }

    /**
     * A cancelled stage has to reach the connection, and the pool is what says
     * whether it did: `ownClient` is one connection wide, so a cancelled
     * exchange that kept its connection would leave the call after it waiting
     * on a server handler this test has not released yet.
     */
    @Test
    fun `cancelling the stage cancels the exchange and frees its connection`() {
        val onOwnClient = KtorHttpTransport(ownClient)
        val stage = onOwnClient.send(ClientRequest(Method.GET, "$base/held")).toCompletableFuture()

        withClue("the server never received the request") {
            arrived.await(GATE_SECONDS, TimeUnit.SECONDS) shouldBe true
        }
        stage.cancel(true) shouldBe true

        val started = System.nanoTime()
        send(ClientRequest(Method.GET, "$base/echo"), on = onOwnClient).text() shouldBe "ok"
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        withClue("the next call waited ${elapsedMs}ms — the cancelled exchange still holds the connection") {
            elapsedMs shouldBeLessThan PROMPT_MILLIS
        }
    }

    /**
     * `ClientTransport.default()` constructs every provider it finds in order
     * to count them, so a transport nobody sends on must cost nothing. The
     * claim is about work *not* done, and the lazy delegate is the only witness
     * to that: an engine built in the constructor would leave this initialised.
     */
    @Test
    fun `a transport nobody sends on builds no client`() {
        val delegate = KtorHttpTransport::class.java.getDeclaredField("running\$delegate")
        delegate.isAccessible = true

        val untouched = delegate.get(KtorHttpTransport()) as Lazy<*>

        untouched.isInitialized() shouldBe false
    }

    /**
     * The client this module keeps is closed by nobody — two transports sharing
     * one must not be able to shut each other down — so its threads must not be
     * what keeps a finished process alive.
     */
    @Test
    fun `the shared client does not hold a finished process open`() {
        call(ClientRequest(Method.GET, "$base/echo"))

        // JUnit starts a watcher of its own partway through a run, which is
        // neither this suite's business nor the client's.
        val since = (Thread.getAllStackTraces().keys - threadsAtStart).filterNot { it.name.startsWith("junit") }

        withClue("the shared client should have started threads, or this asserts nothing") {
            since.shouldNotBeEmpty()
        }
        withClue("a transport nobody was handed a close for must not keep the JVM alive") {
            since.filterNot { it.isDaemon }.shouldBeEmpty()
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
        const val LARGE = 4 * 1024 * 1024
        const val PAUSE_EVERY = 16
        const val SLACK = 4
    }
}
