package io.github.matthewjones372.pelican.client.okhttp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.retryPolicy
import io.github.matthewjones372.pelican.retrying
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.SequenceInputStream
import java.net.InetSocketAddress
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * The adapter over a socket, served by the JDK's own `HttpServer` rather than
 * by `MockWebServer`: a client tested against the server half of the same
 * library proves less about what goes on the wire than one tested against a
 * stranger.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OkHttpTransportTest {

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

    /** What `/flaky` has been sent, and how many times, for the retry test. */
    private val attempts = AtomicInteger()
    private val bodies = ConcurrentLinkedQueue<String>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
        // A handler that blocks must not stop the next request being served,
        // and a non-daemon pool here would be a thread the last test counts.
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
        createContext("/flaky") { exchange -> flaky(exchange) }
        start()
    }

    private val base = "http://localhost:${server.address.port}"
    private val transport = OkHttpTransport()

    /**
     * A client of the caller's own, configured so that a request sent on it is
     * distinguishable from one sent on the shared one, and one call wide —
     * which is what the cancellation test needs.
     */
    private val ownClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "handed-over/1.0").build())
        }
        .dispatcher(Dispatcher().apply { maxRequests = 1 })
        .build()

    /** No jitter and no waiting: the pause is asserted in core, not here. */
    private val impatient = retryPolicy { jitter = 0.0; initialBackoff = Duration.ZERO; retryStreamedBodies = true }

    @BeforeEach
    fun reset() {
        attempts.set(0)
        bodies.clear()
    }

    @AfterAll
    fun tearDown() {
        release.countDown()
        gates.values.forEach { it.countDown() }
        server.stop(0)
        ownClient.dispatcher.executorService.shutdownNow()
        ownClient.connectionPool.evictAll()
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

    /** Fails the first time with a 503 and answers after that. */
    private fun flaky(exchange: HttpExchange) {
        bodies += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        val number = attempts.incrementAndGet()
        val body = if (number == 1) "not now" else "ok"
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(if (number == 1) SERVICE_UNAVAILABLE else OK, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "text/plain")
        exchange.sendResponseHeaders(OK, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun send(request: ClientRequest, on: OkHttpTransport = transport) =
        on.send(request).toCompletableFuture().join()

    /**
     * For the tests that assert on what the server saw rather than on what came
     * back: the body is read and closed, because a response nobody finishes
     * with holds its connection — which the one-call-wide client below would
     * fail on, and rightly.
     */
    private fun call(request: ClientRequest, on: OkHttpTransport = transport) {
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

        response.status shouldBe OK
        response.text() shouldBe "ok"
        seen["method"] shouldBe "POST"
        seen["uri"] shouldBe "/echo?limit=3"
        seen["trace"] shouldBe "abc"
        seen["body"] shouldBe """{"item":"anvil"}"""
    }

    /**
     * OkHttp renders these two off the body and rewrites a header of either
     * name from what the body reports, so a naive crossing sends the caller's
     * twice or contradicts it.
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

    /** The JDK adapter's rendering rather than Ktor's: OkHttp sends one name twice. */
    @Test
    fun `a header the caller wrote twice arrives twice`() {
        call(
            ClientRequest(
                method = Method.GET,
                url = "$base/echo",
                headers = listOf("X-Tag" to "one", "X-Tag" to "two"),
            ),
        )

        repeated["tags"] shouldContainExactly listOf("one", "two")
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

        response.status shouldBe OK
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

    /**
     * What a generated client's file upload is: parts chained as streams under
     * a `multipart/form-data` type nobody measured. The claim is that the
     * envelope goes out chunked — nothing counted it first, which counting
     * would have meant holding the file — and that the part's stream is closed
     * once it has been drained.
     */
    @Test
    fun `a multipart envelope is streamed part by part, and its parts are closed`() {
        val boundary = "PelicanBoundaryTest"
        val closed = AtomicBoolean()
        val file = object : ByteArrayInputStream("1,anvil\n2,rope\n".toByteArray()) {
            override fun close() {
                closed.set(true)
                super.close()
            }
        }

        call(
            ClientRequest(
                method = Method.POST,
                url = "$base/echo",
                headers = listOf("Content-Type" to "multipart/form-data; boundary=$boundary"),
                body = ClientRequest.Body.Streaming { envelope(boundary, file) },
            ),
        )

        seen["encoding"] shouldBe "chunked"
        seen["length"] shouldBe ""
        seen["body"].orEmpty() shouldContain "1,anvil"
        seen["body"].orEmpty() shouldContain """name="orders"; filename="orders.csv""""
        withClue("the part's stream was left open after the transport drained it") { closed.get() shouldBe true }
    }

    private fun envelope(boundary: String, file: InputStream): InputStream = SequenceInputStream(
        Collections.enumeration(
            listOf(
                ByteArrayInputStream(
                    (
                        "--$boundary\r\nContent-Disposition: form-data; name=\"orders\"; " +
                            "filename=\"orders.csv\"\r\nContent-Type: text/csv\r\n\r\n"
                        ).toByteArray(),
                ),
                file,
                ByteArrayInputStream("\r\n--$boundary--\r\n".toByteArray()),
            ),
        ),
    )

    @Test
    fun `response headers survive the crossing, the body's two among them`() {
        val response = send(ClientRequest(Method.GET, "$base/echo"))

        response.header("X-Answer") shouldBe "42"
        response.header("x-answer") shouldBe "42"
        response.header("X-Absent") shouldBe null
        response.header("Content-Type") shouldBe "text/plain"
        response.headers.map { it.first.lowercase() } shouldContainAll listOf("x-answer", "content-length")
        withClue("OkHttp hands back what it received; adding either back from the body would double it") {
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
     * OkHttp reads from the socket only when the caller reads, so a slow reader
     * backpressures rather than filling memory — as long as nothing on the way
     * out of the adapter buffers it first.
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
        val started = System.nanoTime()
        val failure = assertFailsWith<CompletionException> {
            send(ClientRequest(Method.GET, "$base/slow").withTimeout(Duration.ofMillis(TIMEOUT_MILLIS)))
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        failure.cause.shouldBeInstanceOf<InterruptedIOException>()
        failure.cause!!.message.orEmpty() shouldContain "$base/slow"
        elapsedMs shouldBeLessThan SLOW_MILLIS
    }

    /**
     * The reason the deadline is not OkHttp's `callTimeout`, which would have
     * ended this body mid-read. What a `ClientRequest.timeout` bounds is the
     * arrival of the response head; a streamed response outliving it is the
     * endpoint working rather than failing.
     */
    @Test
    fun `the deadline bounds the response head and lets the body run past it`() {
        val response = send(
            ClientRequest(Method.GET, "$base/drip?gate=deadline").withTimeout(Duration.ofMillis(TIMEOUT_MILLIS)),
        )

        response.body.use { body ->
            body.readNBytes(3).toString(Charsets.UTF_8) shouldBe "one"
            Thread.sleep(TIMEOUT_MILLIS * SLACK)
            gate("gate=deadline").countDown()
            body.readBytes().toString(Charsets.UTF_8) shouldBe "two"
        }
    }

    @Test
    fun `a response closed unread leaves the transport usable`() {
        send(ClientRequest(Method.GET, "$base/bulk?bytes=$BULK")).body.close()

        send(ClientRequest(Method.GET, "$base/echo")).text() shouldBe "ok"
    }

    /**
     * Not just that a handed-over client is accepted: that the request went out
     * on it, which the user agent its interceptor writes is the evidence for.
     */
    @Test
    fun `a client handed over is the one the request is sent on`() {
        call(ClientRequest(Method.GET, "$base/echo"), on = OkHttpTransport(ownClient))

        seen["agent"] shouldBe "handed-over/1.0"
    }

    /**
     * A cancelled stage has to reach the call, and the dispatcher is what says
     * whether it did: `ownClient` runs one call at a time, so a cancelled
     * exchange that kept its slot would leave the call after it waiting on a
     * server handler this test has not released yet.
     */
    @Test
    fun `cancelling the stage cancels the call and frees its slot`() {
        val onOwnClient = OkHttpTransport(ownClient)
        val stage = onOwnClient.send(ClientRequest(Method.GET, "$base/held")).toCompletableFuture()

        withClue("the server never received the request") {
            arrived.await(GATE_SECONDS, TimeUnit.SECONDS) shouldBe true
        }
        stage.cancel(true) shouldBe true

        val started = System.nanoTime()
        call(ClientRequest(Method.GET, "$base/echo"), on = onOwnClient)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        withClue("the next call waited ${elapsedMs}ms — the cancelled call still holds the dispatcher") {
            elapsedMs shouldBeLessThan PROMPT_MILLIS
        }
    }

    /**
     * The point of `Body.Streaming` carrying a function rather than a stream,
     * asserted through `RetryingTransport`: the first attempt drains what the
     * function opened, and the second has to be given a new one rather than the
     * drained tail of the last.
     */
    @Test
    fun `a streamed body is opened again for the attempt that follows it`() {
        val retrying = transport.retrying(impatient)

        val response = retrying
            .send(
                ClientRequest(
                    method = Method.PUT,
                    url = "$base/flaky",
                    body = ClientRequest.Body.Streaming { ByteArrayInputStream("anvil".toByteArray()) },
                ),
            )
            .toCompletableFuture()
            .join()

        response.status shouldBe OK
        attempts.get() shouldBe 2
        bodies.toList() shouldBe listOf("anvil", "anvil")
    }

    /**
     * `ClientTransport.default()` constructs every provider it finds in order
     * to count them, so a transport nobody sends on must cost nothing. The
     * claim is about work *not* done, and the lazy delegate is the only witness
     * to that: a client built in the constructor would leave this initialised.
     */
    @Test
    fun `a transport nobody sends on builds no client`() {
        val delegate = OkHttpTransport::class.java.getDeclaredField("running\$delegate")
        delegate.isAccessible = true

        val untouched = delegate.get(OkHttpTransport()) as Lazy<*>

        untouched.isInitialized() shouldBe false
    }

    /**
     * The client this module keeps is closed by nobody — two transports sharing
     * one must not be able to shut each other down — so its threads must not be
     * what keeps a finished process alive. OkHttp's own dispatcher pool is not
     * daemon; the one here is.
     */
    @Test
    fun `the shared client does not hold a finished process open`() {
        call(ClientRequest(Method.GET, "$base/echo"))

        val live = Thread.getAllStackTraces().keys.filter { it.name.startsWith("pelican-client-okhttp") }

        withClue("the shared client should have started threads, or this asserts nothing") {
            live.shouldNotBeEmpty()
        }
        withClue("a transport nobody was handed a close for must not keep the JVM alive") {
            live.count { !it.isDaemon } shouldBe 0
        }
    }

    private companion object {
        const val OK = 200
        const val NO_CONTENT = 204
        const val SERVICE_UNAVAILABLE = 503
        const val SLOW_MILLIS = 2_000L
        const val TIMEOUT_MILLIS = 200L
        const val GATE_SECONDS = 10L
        const val PROMPT_MILLIS = 2_000L
        const val CHUNK = 8 * 1024
        const val BULK = 1024 * 1024
        const val PAUSE_EVERY = 16
        const val SLACK = 4
    }
}
