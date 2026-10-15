package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.NetworkConnector
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.eclipse.jetty.util.thread.VirtualThreadPool
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class PoolsTest {
    private val where = endpoint {
        get("where")
        text()
    }

    private val routes = listOf(
        where handledNow { "${Thread.currentThread().isVirtual} ${Thread.currentThread().name}" },
    )

    @Test
    fun `the virtual pool serves handlers that run on virtual threads`() {
        pelicanPool(Handlers.onVirtualThreads, JettyPool.VIRTUAL).shouldBeInstanceOf<VirtualThreadPool>()
    }

    @Test
    fun `handlers promised a platform thread or their own executor keep the queued pool`() {
        pelicanPool(Handlers.onDispatcher, JettyPool.VIRTUAL).shouldBeInstanceOf<QueuedThreadPool>()
        Executors.newSingleThreadExecutor().use { executor ->
            pelicanPool(Handlers.on(executor), JettyPool.VIRTUAL).shouldBeInstanceOf<QueuedThreadPool>()
        }
    }

    @Test
    fun `on the virtual pool a synchronous handler hops off jetty's selector, which is a virtual thread too`() {
        val server = Server(pelicanPool(Handlers.onVirtualThreads, JettyPool.VIRTUAL))
        server.addConnector(ServerConnector(server, HttpConnectionFactory()))
        server.handler = api(routes, JacksonCodecs).toHandler(Handlers.onVirtualThreads)
        server.start()
        try {
            Asking(emptyList()).use { asking ->
                val port = (server.connectors.single() as NetworkConnector).localPort
                val (virtual, name) = asking.ask("http://127.0.0.1:$port", "GET", "/where").body.split(" ")
                virtual shouldBe "true"
                name shouldNotStartWith "pelican-"
            }
        } finally {
            server.stop()
        }
    }
}
