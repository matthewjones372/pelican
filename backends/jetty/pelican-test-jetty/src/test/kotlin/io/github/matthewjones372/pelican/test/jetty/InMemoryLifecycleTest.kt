package io.github.matthewjones372.pelican.test.jetty

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.jetty.start
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

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

class InMemoryLifecycleTest {

    @Test
    fun `requests run with no server bound`() {
        pingApi().inMemory(Handlers.onDispatcher).use { app ->
            app.call(ping, Unit) shouldBe "pong"
        }
    }

    @Test
    fun `closing waits for the server to actually stop`() {
        val app = pingApi().inMemory(Handlers.onDispatcher)
        val server = (app.transport as InMemoryTransport).server

        server.isStarted shouldBe true
        app.close()
        withClue("close() returned before the server had stopped") {
            server.isStopped shouldBe true
        }
    }

    /** Jetty's default compliance answers this with its own HTML 400 before the handler sees it. */
    @Test
    fun `an encoded slash reaches the handler as part of one value`() {
        pingApi().inMemory().use { app ->
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
