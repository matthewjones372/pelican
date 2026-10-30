package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.AdmissionDecision
import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.OperationAdmission
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.rawBody
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.eclipse.jetty.http.HttpTester
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AdmissionJettyCleanupTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `refusal is written before arriving chunks then the same connection serves another request`(admitted: Boolean) {
        val world = CleanupWorld(admitted)
        world.service.admissionOverJetty(wrap = world.reads::wrap) { connector ->
            val connection = connector.executeRequest(CLEANUP_HEADERS)
            try {
                HttpTester.parseResponse(connection.getResponse()).status shouldBe world.status
                world.reads.completions.get() shouldBe 0
                world.reads.bytes.get() shouldBe 0
                world.handled.get() shouldBe 0
                connection.addInput("3\r\none\r\n")
                connection.addInput("3\r\ntwo\r\n0\r\n\r\n")
                world.reads.finished.await(CLEANUP_GATE_SECONDS, TimeUnit.SECONDS) shouldBe true
                world.reads.bytes.get() shouldBe 6
                world.reads.assertReleasedOnce()
                connection.addInput("GET /later HTTP/1.1\r\nHost: unit\r\n\r\n")
                val later = HttpTester.parseResponse(connection.getResponse())
                later.status shouldBe 200
                later.content shouldBe "later"
                connection.isOpen shouldBe true
                world.checked.get() shouldBe if (admitted) 2 else 0
                world.handled.get() shouldBe 0
            } finally {
                connection.close()
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `large unread body drains only the native bound and releases each observed chunk once`(admitted: Boolean) {
        val world = CleanupWorld(admitted)
        world.service.admissionOverJetty(wrap = world.reads::wrap) { connector ->
            val connection = connector.executeRequest(CLEANUP_HEADERS)
            try {
                HttpTester.parseResponse(connection.getResponse()).status shouldBe world.status
                val body = "x".repeat(CLEANUP_DRAIN_BOUND.toInt() * 2)
                connection.addInput("${body.length.toString(16)}\r\n$body\r\n0\r\n\r\n")
                world.reads.finished.await(CLEANUP_GATE_SECONDS, TimeUnit.SECONDS) shouldBe true
                world.reads.bytes.get() shouldBeGreaterThan CLEANUP_DRAIN_BOUND
                world.reads.bytes.get() shouldBeLessThanOrEqual CLEANUP_DRAIN_BOUND + world.reads.largest.get()
                (world.reads.bytes.get() < body.length) shouldBe true
                world.reads.assertReleasedOnce()
                world.handled.get() shouldBe 0
                world.checked.get() shouldBe if (admitted) 1 else 0
                connection.waitUntilClosed()
                connection.isOpen shouldBe false
            } finally {
                connection.close()
            }
        }
    }

    @ParameterizedTest
    @CsvSource("false,EOF", "true,EOF", "false,CLOSED", "true,CLOSED")
    fun `failed or closed unread input completes cleanup like a native caller refusal`(
        admitted: Boolean,
        failure: String,
    ) {
        val world = CleanupWorld(admitted)
        world.service.admissionOverJetty(wrap = world.reads::wrap) { connector ->
            val connection = connector.executeRequest(CLEANUP_HEADERS)
            try {
                HttpTester.parseResponse(connection.getResponse()).status shouldBe world.status
                connection.addInput("3\r\none\r\n")
                world.reads.waitingAfterData.await(CLEANUP_GATE_SECONDS, TimeUnit.SECONDS) shouldBe true
                world.reads.bytes.get() shouldBe 3
                world.reads.completions.get() shouldBe 0
                if (failure == "EOF") connection.addInputEOF() else connection.close()
                world.reads.finished.await(CLEANUP_GATE_SECONDS, TimeUnit.SECONDS) shouldBe true
                world.reads.failures.get() shouldBeGreaterThan 0
                world.reads.assertReleasedOnce()
                world.handled.get() shouldBe 0
                world.checked.get() shouldBe if (admitted) 1 else 0
                connection.waitUntilClosed()
                connection.isOpen shouldBe false
            } finally {
                connection.close()
            }
        }
    }
}

private class CleanupWorld(admitted: Boolean) {
    val reads = CleanupReads()
    val checked = AtomicInteger()
    val handled = AtomicInteger()
    val status = if (admitted) 403 else 401
    private val scheme = bearerAuth()
    private val caller = authenticated(scheme) { it.subject }
    private val blocked = endpoint(rawBody()) { post("blocked"); authenticatedBy(caller); text() }
    private val later = endpoint { get("later"); text() }
    val service = api(
        listOf(
            blocked handledNow { handled.incrementAndGet(); error("refused body was opened") },
            later handledNow { "later" },
        ),
        JacksonCodecs,
    ) {
        authenticate(scheme, Authenticator { null })
        if (admitted) admission(
            OperationAdmission { request ->
                checked.incrementAndGet()
                if (request.endpoint === blocked) AdmissionDecision.Refuse(403) else AdmissionDecision.Continue
            },
        )
    }
}

/** Observes native read/release/completion without replacing Jetty's bytes, failure or demand behavior. */
private class CleanupReads {
    val bytes = AtomicLong()
    val largest = AtomicLong()
    val failures = AtomicInteger()
    val completions = AtomicInteger()
    val finished = CountDownLatch(1)
    val waitingAfterData = CountDownLatch(1)
    private val releases = ConcurrentLinkedQueue<AtomicInteger>()

    fun wrap(handler: Handler): Handler = object : Handler.Wrapper(handler) {
        override fun handle(request: Request, response: Response, callback: Callback): Boolean {
            if (request.httpURI.path != "/blocked") return super.handle(request, response, callback)
            val observed = object : Request.Wrapper(request) {
                override fun demand(demandCallback: Runnable) {
                    super.demand(demandCallback)
                    if (bytes.get() > 0) waitingAfterData.countDown()
                }

                override fun read(): Content.Chunk? {
                    val chunk = super.read() ?: return null
                    val released = AtomicInteger()
                    releases += released
                    bytes.addAndGet(chunk.remaining().toLong())
                    largest.accumulateAndGet(chunk.remaining().toLong(), ::maxOf)
                    if (Content.Chunk.isFailure(chunk)) failures.incrementAndGet()
                    return object : Content.Chunk by chunk {
                        override fun release(): Boolean { released.incrementAndGet(); return chunk.release() }
                    }
                }
            }
            val completed = object : Callback by callback {
                override fun succeeded() {
                    completions.incrementAndGet()
                    try { callback.succeeded() } finally { finished.countDown() }
                }
                override fun failed(x: Throwable) {
                    completions.incrementAndGet()
                    try { callback.failed(x) } finally { finished.countDown() }
                }
            }
            return super.handle(observed, response, completed)
        }
    }

    fun assertReleasedOnce() {
        completions.get() shouldBe 1
        releases.size shouldBeGreaterThan 0
        releases.forEach { it.get() shouldBe 1 }
    }
}

private const val CLEANUP_DRAIN_BOUND = 1024L * 1024L
private const val CLEANUP_GATE_SECONDS = 10L
private const val CLEANUP_HEADERS = "POST /blocked HTTP/1.1\r\nHost: unit\r\n" +
    "Authorization: Bearer synthetic\r\nTransfer-Encoding: chunked\r\n\r\n"
