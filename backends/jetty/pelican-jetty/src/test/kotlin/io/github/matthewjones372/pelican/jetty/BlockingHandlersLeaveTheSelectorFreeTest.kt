package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.matchers.shouldBe
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.NetworkConnector
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Spec 0085: Jetty may call Pelican on its selector, so a handler that blocks must never block there. */
class BlockingHandlersLeaveTheSelectorFreeTest {

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
    fun `while synchronous handlers block, a 404 and an asynchronous handler are still answered`() {
        // One selector, and fewer pool threads than there are blocked handlers.
        val server = Server(pelicanThreads(Handlers.onVirtualThreads, POOL_THREADS).apply { reservedThreads = 0 })
        val http = HttpConfiguration().apply { uriCompliance = PELICAN_URI_COMPLIANCE }
        server.addConnector(ServerConnector(server, 1, 1, HttpConnectionFactory(http)))
        server.handler = blocking.toHandler(Handlers.onVirtualThreads)
        server.defaultHandler = Unanswered()
        server.start()
        try {
            val base = "http://127.0.0.1:${(server.connectors.single() as NetworkConnector).localPort}"
            val held = (1..BLOCKED).map { get("$base/blocked") }
            entered.await(GATE_SECONDS, TimeUnit.SECONDS) shouldBe true

            get("$base/nowhere").get(GATE_SECONDS, TimeUnit.SECONDS).statusCode() shouldBe 404
            get("$base/later").get(GATE_SECONDS, TimeUnit.SECONDS).body() shouldBe "later"
            held.none { it.isDone } shouldBe true

            release.countDown()
            held.map { it.get(GATE_SECONDS, TimeUnit.SECONDS).body() }.distinct() shouldBe listOf("released")
        } finally {
            release.countDown()
            client.close()
            server.stop()
        }
    }

    private companion object {
        const val BLOCKED = 8
        const val POOL_THREADS = 4
        const val GATE_SECONDS = 30L
    }
}
