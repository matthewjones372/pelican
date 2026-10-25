package io.github.matthewjones372.pelican.test.gannet

import io.github.matthewjones372.gannet.GannetConfig
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.gannet.Handlers
import io.github.matthewjones372.pelican.gannet.RequiresGannet
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.start
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.Socket

private val ping = endpoint {
    get("ping")
    text()
}

private val value = pathParam<String>("value")

private val echo = endpoint(value) {
    get("echo" / value)
    text()
}

private fun pingApi() = api(endpoints = listOf(ping handledNow { "pong" }, echo handledNow { it }))

@RequiresGannet
class LoopbackLifecycleTest {

    @Test
    fun `requests run against a server on a port of the kernel's choosing`() {
        pingApi().onLoopback(Handlers.onDispatcher, GannetConfig(carriers = 1)).use { app ->
            app.call(ping, Unit) shouldBe "pong"
        }
    }

    @Test
    fun `closing stops the server and frees its port`() {
        val app = pingApi().onLoopback()
        val port = (app.transport as LoopbackTransport).server.port

        app.close()
        shouldThrow<ConnectException> { Socket("127.0.0.1", port).close() }
    }

    @Test
    fun `an encoded slash reaches the handler as part of one value`() {
        pingApi().onLoopback().use { app ->
            app.call(echo, "a/b") shouldBe "a/b"
        }
    }

    @Test
    fun `a started server's client calls it over a socket`() {
        pingApi().start(port = 0).use { server ->
            server.client().use { app ->
                app.call(ping, Unit) shouldBe "pong"
            }
        }
    }
}
