package io.github.matthewjones372.pelican.test.pekko

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.pekko.handledNow
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.Transport
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val ping = endpoint {
    get("ping")
    text()
}

private fun pingApi() = api(endpoints = listOf(ping handledNow { "pong" }))

class InMemoryLifecycleTest {

    @Test
    fun `requests run with no server bound`() {
        pingApi().inMemory("lifecycle-serves").use { app ->
            app.call(ping, Unit) shouldBe "pong"
        }
    }

    @Test
    fun `closing waits for the actor system to actually terminate`() {
        val app = pingApi().inMemory("lifecycle-closes")
        val system = (app.transport as InMemoryTransport).system

        system.getWhenTerminated().toCompletableFuture().isDone shouldBe false
        app.close()
        withClue("close() returned before the system had terminated") {
            system.getWhenTerminated().toCompletableFuture().isDone shouldBe true
        }
    }

    /** A borrowed system outlives the client that used it. */
    @Test
    fun `a shared system is not shut down by the client`() {
        val owner = pingApi().inMemory("lifecycle-shared")
        val system = (owner.transport as InMemoryTransport).system
        try {
            pingApi().inMemory(system).use { borrower ->
                borrower.call(ping, Unit) shouldBe "pong"
            }
            system.getWhenTerminated().toCompletableFuture().isDone shouldBe false
        } finally {
            owner.close()
        }
    }
}
