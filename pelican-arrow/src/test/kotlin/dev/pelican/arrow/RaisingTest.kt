package dev.pelican.arrow

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.right
import dev.pelican.ApiError
import dev.pelican.Endpoint
import dev.pelican.Outcome
import dev.pelican.Params
import dev.pelican.ServerEndpoint
import dev.pelican.div
import dev.pelican.endpoint
import dev.pelican.errorJson
import dev.pelican.ok
import dev.pelican.orFail
import dev.pelican.pathParam
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * What the binders here have to get right is one thing: a raised value must
 * come out as the failure the endpoint *declared*, because that is what fixes
 * the status. So these tests look at the [Outcome] the bound handler produces
 * rather than at a rendered response — no backend is involved, which is also
 * the claim this module makes about itself.
 *
 * The rendering of that Outcome is not re-tested here. It is the same value
 * `handledOrFail` produces, and each backend already has tests for turning one
 * into a response; the arrow modules for Pekko, http4k and Ktor check that the
 * two paths really do agree, over a real request.
 */
class RaisingTest {

    data class Item(val id: Long, val name: String)

    /** The domain's own errors. Nothing here knows about HTTP. */
    sealed interface Problem {
        data class Unknown(val id: Long) : Problem
        data object Locked : Problem
    }

    private val noSuchItem = errorJson<ApiError>(404, "No item with that id")
    private val itemLocked = errorJson<ApiError>(423, "Someone else is editing it")

    /** Two failures with the same payload type: only the mapping tells them apart. */
    private val toResponse: ErrorMapper<Problem, ApiError> = { problem ->
        when (problem) {
            is Problem.Unknown -> noSuchItem(ApiError(404, "No item ${problem.id}"))
            is Problem.Locked -> itemLocked(ApiError(423, "Locked"))
        }
    }

    private val itemId = pathParam<Long>("itemId")

    /** Both failures are declared, so the mapper has a real choice to make. */
    private val getItem = endpoint(itemId) {
        get("items" / itemId)
        json<Item>().orFail(noSuchItem, itemLocked)
    }

    private fun paramsFor(id: Long, endpoint: Endpoint<*, *>) =
        Params(mapOf(itemId to id), underlying = null, endpoint = endpoint)

    /** What the interpreter would receive, unwrapped. */
    private fun ServerEndpoint.outcomeFor(id: Long): Outcome<*, *> =
        invoke(paramsFor(id, endpoint)).toCompletableFuture().get() as Outcome<*, *>

    // ------------------------------------------------------------- binders

    @Test
    fun `a handler that returns produces Ok`() {
        val bound = getItem.handledRaise(toResponse) { id -> Item(id, "widget") }

        assertEquals(Outcome.Ok(Item(7, "widget")), bound.outcomeFor(7))
    }

    @Test
    fun `a raised value becomes the failure the mapper names`() {
        val bound = getItem.handledRaise(toResponse) { id ->
            raise(Problem.Unknown(id))
        }

        val err = bound.outcomeFor(7) as Outcome.Err<*>
        assertSame(noSuchItem, err.declared, "the raise picked the wrong declared failure")
        assertEquals(ApiError(404, "No item 7"), err.error)
    }

    /** The mapper, not the payload type, is what chooses between two failures. */
    @Test
    fun `two failures sharing a payload type stay distinguishable`() {
        val bound = getItem.handledRaise(toResponse) { _ -> raise(Problem.Locked) }

        val err = bound.outcomeFor(7) as Outcome.Err<*>
        assertSame(itemLocked, err.declared)
        assertEquals(423, err.declared.status)
    }

    /** Params is still the receiver: a Raise handler can set a response header. */
    @Test
    fun `the handler still has Params in scope`() {
        var seen: Endpoint<*, *>? = null
        val bound = getItem.handledRaise(toResponse) { id ->
            seen = endpoint
            Item(id, "widget")
        }

        bound.outcomeFor(7)
        assertSame(getItem, seen)
    }

    @Test
    fun `the async binder raises before it hands back the stage`() {
        val bound = getItem.handledByRaise(toResponse) { id ->
            raise(Problem.Unknown(id))
        }

        val err = bound.outcomeFor(1) as Outcome.Err<*>
        assertSame(noSuchItem, err.declared)
    }

    @Test
    fun `the async binder wraps the stage's value in Ok`() {
        val bound = getItem.handledByRaise(toResponse) { id ->
            CompletableFuture.completedStage(Item(id, "widget"))
        }

        assertEquals(Outcome.Ok(Item(3, "widget")), bound.outcomeFor(3))
    }

    // --------------------------------------------------------- conversions

    @Test
    fun `an Either's right is Ok and its left goes through the mapper`() {
        assertEquals(Outcome.Ok(Item(1, "widget")), Item(1, "widget").right().toOutcome(toResponse))

        val err = Problem.Locked.left().toOutcome(toResponse) as Outcome.Err<*>
        assertSame(itemLocked, err.declared)
    }

    @Test
    fun `an Outcome converts back to an Either, losing which failure it was`() {
        val err: Outcome<ApiError, Int> = noSuchItem(ApiError(404, "gone"))

        assertEquals(ApiError(404, "gone").left(), err.toEither())
        assertEquals(1.right(), ok(1).toEither())
    }

    /** A Raise block can be run without a handler at all. */
    @Test
    fun `raising is usable on its own`() {
        val params = Params(emptyMap(), underlying = null)
        val f: context(Raise<Problem>)
        Params.(Long) -> Item = { id -> raise(Problem.Unknown(id)) }

        val out = params.raising(9L, toResponse, f)
        assertSame(noSuchItem, (out as Outcome.Err).declared)
    }

    /**
     * The DSL in Dsl.kt is what makes `raise` and `ensure` resolve against a
     * context parameter. Without it the lambdas above would not compile, so
     * this checks the other two entry points as well.
     */
    @Test
    fun `ensure and ensureNotNull raise through the same mapping`() {
        val bound = getItem.handledRaise(toResponse) { id ->
            ensure(id > 0) { Problem.Locked }
            val found: Item? = if (id == 1L) Item(1, "widget") else null
            ensureNotNull(found) { Problem.Unknown(id) }
        }

        assertEquals(Outcome.Ok(Item(1, "widget")), bound.outcomeFor(1))
        assertSame(noSuchItem, (bound.outcomeFor(2) as Outcome.Err).declared)
        assertSame(itemLocked, (bound.outcomeFor(-1) as Outcome.Err).declared)
    }

    /** An Either from code written before anyone thought about HTTP. */
    @Test
    fun `bind raises the left of an Either`() {
        fun lookUp(id: Long): Either<Problem, Item> =
            if (id == 1L) Item(1, "widget").right() else Problem.Unknown(id).left()

        val bound = getItem.handledRaise(toResponse) { id -> lookUp(id).bind() }

        assertEquals(Outcome.Ok(Item(1, "widget")), bound.outcomeFor(1))
        assertSame(noSuchItem, (bound.outcomeFor(2) as Outcome.Err).declared)
    }
}
