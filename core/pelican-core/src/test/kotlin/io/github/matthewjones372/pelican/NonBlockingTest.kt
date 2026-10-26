package io.github.matthewjones372.pelican

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/** Spec 0090: `nonBlocking()` is chosen per binding, and nothing else about the binding changes with it. */
class NonBlockingTest {

    private val ping = endpoint {
        get("ping")
        text()
    }

    private val handler: (Params) -> CompletableFuture<Any?> = { CompletableFuture.completedFuture("pong") }
    private val stamp = Filter { p, next -> next(p) }

    @Test
    fun `a binding is not non-blocking until it says so`() {
        ServerEndpoint(ping, handler).isNonBlocking shouldBe false
    }

    @Test
    fun `marking a binding keeps its endpoint, handler and filters`() {
        val bound = ServerEndpoint(ping, handler).filteredBy(stamp)
        val marked = bound.nonBlocking()

        marked.isNonBlocking shouldBe true
        marked.endpoint shouldBeSameInstanceAs ping
        marked.invoke shouldBeSameInstanceAs bound.invoke
        marked.filters shouldBe listOf(stamp)
    }

    @Test
    fun `a filter attached after the mark keeps it`() {
        ServerEndpoint(ping, handler).nonBlocking().filteredBy(stamp).isNonBlocking shouldBe true
    }
}
