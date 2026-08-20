package dev.pelican.test.pekko

import dev.pelican.Api
import dev.pelican.endpoint
import dev.pelican.noInputs
import dev.pelican.pekko.handledNow
import dev.pelican.test.ApiClient
import dev.pelican.test.RequestSpec
import dev.pelican.test.ResponseSpec
import dev.pelican.test.Transport
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * An API with no codec configured at all — a text output needs none — which
 * also keeps this module's tests free of a JSON library.
 */
private val ping = endpoint(noInputs) {
    get("ping")
    text()
}

private fun pingApi() = Api(
    endpoints = listOf(ping handledNow { "pong" }),
)

class InMemoryLifecycleTest {

    @Test
    fun `requests run with no server bound`() {
        pingApi().inMemory("lifecycle-serves").use { app ->
            assertEquals("pong", app.call(ping, Unit))
        }
    }

    /**
     * `terminate()` is asynchronous. Closing must not report success while the
     * system is still running, or a suite that opens several clients quietly
     * accumulates thread pools.
     */
    @Test
    fun `closing waits for the actor system to actually terminate`() {
        val app = pingApi().inMemory("lifecycle-closes")
        val system = (app.transport as InMemoryTransport).system

        assertFalse(system.getWhenTerminated().toCompletableFuture().isDone)
        app.close()
        assertTrue(
            system.getWhenTerminated().toCompletableFuture().isDone,
            "close() returned before the system had terminated",
        )
    }

    /** A borrowed system outlives the client that used it. */
    @Test
    fun `a shared system is not shut down by the client`() {
        val owner = pingApi().inMemory("lifecycle-shared")
        val system = (owner.transport as InMemoryTransport).system
        try {
            pingApi().inMemory(system).use { borrower ->
                assertEquals("pong", borrower.call(ping, Unit))
            }
            assertFalse(system.getWhenTerminated().toCompletableFuture().isDone)
        } finally {
            owner.close()
        }
    }
}
