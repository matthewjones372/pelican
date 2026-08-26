package io.github.matthewjones372.pelican.ktor

import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.ok
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * A bound handler is a handler, whatever asked for it.
 *
 * Ktor's binders run a `suspend` handler as a child of the call, which is what
 * makes the interpreter's await a real suspension — and left anything invoking
 * a binding without a request casting `null` to an `ApplicationCall`. A tool
 * call through `mcpDispatch` is exactly that: the same `ServerEndpoint`, the
 * same handler, and no socket behind it.
 */
class WithoutACallTest {

    @Test
    fun `a handler bound for Ktor runs when it is invoked without a request`() {
        val bound = getItem handledOrFail { id -> ok(Item(id, "item-$id")) }

        val result = bound.invoke(
            Params(mapOf(itemId as ParamKey<*> to 7L), underlying = null, endpoint = getItem),
        ).toCompletableFuture().join()

        (result as Outcome.Ok<*>).value shouldBe Item(7, "item-7")
    }
}
