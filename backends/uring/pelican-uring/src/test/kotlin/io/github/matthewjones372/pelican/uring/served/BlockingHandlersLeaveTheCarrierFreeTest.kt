package io.github.matthewjones372.pelican.uring.served

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.uring.Handlers
import io.github.matthewjones372.pelican.uring.RequiresUring
import io.github.matthewjones372.pelican.uring.UringConfig
import io.github.matthewjones372.pelican.uring.handledBy
import io.github.matthewjones372.pelican.uring.handledNow
import io.github.matthewjones372.pelican.uring.start
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Spec 0088: routing and asynchronous handlers stay on the carrier, so a blocked handler pool holds up neither. */
@RequiresUring
class BlockingHandlersLeaveTheCarrierFreeTest {

    private val blocked = endpoint {
        get("blocked")
        text()
    }

    private val later = endpoint {
        get("later")
        text()
    }

    private val entered = CountDownLatch(BLOCKED)
    private val release = CountDownLatch(1)

    private val blocking = api(
        listOf(
            blocked handledNow {
                entered.countDown()
                release.await(GATE_SECONDS, TimeUnit.SECONDS)
                "released"
            },
            later handledBy { CompletableFuture.completedFuture("later") },
        ),
        JacksonCodecs,
    )

    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    private fun get(url: String): CompletableFuture<HttpResponse<String>> =
        client.sendAsync(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `while every handler thread blocks, a 404 and an asynchronous handler are still answered`() {
        val pool = Executors.newFixedThreadPool(BLOCKED)
        val server = blocking.start(port = 0, handlers = Handlers.on(pool), config = UringConfig(carriers = 1))
        try {
            val held = (1..BLOCKED).map { get("${server.baseUrl}/blocked") }
            entered.await(GATE_SECONDS, TimeUnit.SECONDS) shouldBe true

            get("${server.baseUrl}/nowhere").get(GATE_SECONDS, TimeUnit.SECONDS).statusCode() shouldBe 404
            get("${server.baseUrl}/later").get(GATE_SECONDS, TimeUnit.SECONDS).body() shouldBe "later"
            held.none { it.isDone } shouldBe true

            release.countDown()
            held.map { it.get(GATE_SECONDS, TimeUnit.SECONDS).body() }.distinct() shouldBe listOf("released")
        } finally {
            release.countDown()
            server.stop()
            pool.shutdown()
        }
    }

    private companion object {
        const val BLOCKED = 2
        const val GATE_SECONDS = 30L
    }
}
