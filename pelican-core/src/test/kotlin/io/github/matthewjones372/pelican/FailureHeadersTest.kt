package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * A declared failure that carries headers as well as a payload.
 *
 * The bargain is the one `emits(...)` makes on the success response, moved to
 * where a failure can keep it: the values go on the failure itself, so a
 * `Retry-After` declared on a 429 travels with the 429 and cannot reach the
 * success the same handler might have returned instead.
 */
class FailureHeadersTest {

    data class Problem(val code: String)

    private val retryAfter = responseHeader<Long>("Retry-After", "Seconds to wait")
    private val quota = responseHeader<Int>("X-Quota").optional()
    private val undeclared = responseHeader<String>("X-Sneaky")

    private val throttled = errorJson<Problem>(429, "Too many requests", retryAfter, quota)
    private val missing = errorJson<Problem>(404, "No widget with that id")

    @Test
    fun `a declared header travels with the failure, encoded by its own codec`() {
        val err = throttled(Problem("slow-down"), retryAfter of 30L, quota of 0) as Outcome.Err

        err.error shouldBe Problem("slow-down")
        err.headers shouldBe listOf("Retry-After" to "30", "X-Quota" to "0")
    }

    @Test
    fun `in declaration order, whatever order the call listed them in`() {
        val err = throttled(Problem("slow-down"), quota of 3, retryAfter of 1L) as Outcome.Err

        err.headers.map { it.first } shouldBe listOf("Retry-After", "X-Quota")
    }

    @Test
    fun `a failure that declares no headers is produced exactly as it was before`() {
        val err = missing(Problem("gone")) as Outcome.Err

        err.headers shouldBe emptyList()
    }

    @Test
    fun `sending a header the failure never declared throws`() {
        val failure = shouldThrow<IllegalStateException> {
            throttled(Problem("slow-down"), retryAfter of 30L, undeclared of "surprise")
        }
        failure.message shouldContain "never declared it"
    }

    /**
     * Stricter than `Params.setHeader`, which reports a missing required header
     * rather than failing on it — and it can be, because this call is the whole
     * answer rather than one of several a handler might still be making.
     */
    @Test
    fun `a required header the call left out throws, naming it`() {
        val failure = shouldThrow<IllegalStateException> { throttled(Problem("slow-down")) }

        failure.message shouldContain "Retry-After"
        failure.message shouldContain "optional()"
    }

    @Test
    fun `an optional one left out is simply not sent`() {
        val err = throttled(Problem("slow-down"), retryAfter of 30L) as Outcome.Err

        err.headers shouldBe listOf("Retry-After" to "30")
    }

    @Test
    fun `the value reads back as the type it was declared as`() {
        val err = throttled(Problem("slow-down"), retryAfter of 30L) as Outcome.Err

        err[retryAfter] shouldBe 30L
        // Declared, optional, and not sent — which is an answer rather than a
        // fault, and is also what a client reading a failure back sees.
        err[quota].shouldBeNull()
    }

    /**
     * The reading end again, and the case that loses the failure just as
     * thoroughly as an absent header does: a server that promised a `Long` and
     * sent `soon`.
     */
    @Test
    fun `a header that arrived but does not decode reads as null rather than throwing`() {
        // What the client builds from a response, header and all.
        val err = Outcome.Err(throttled, Problem("slow-down"), listOf("Retry-After" to "soon"))

        err[retryAfter].shouldBeNull()
        // The point of it being null: the failure that did arrive survives to
        // be asserted on.
        err.error shouldBe Problem("slow-down")
    }

    @Test
    fun `the same header cannot be declared twice on one failure`() {
        val clash = shouldThrow<IllegalArgumentException> {
            errorJson<Problem>(429, "Too many requests", retryAfter, responseHeader<Long>("retry-after"))
        }
        clash.message shouldContain "more than once"
    }

    @Test
    fun `the headers are documented on that failure's response, and nowhere else`() {
        val widgetId = pathParam<Long>("widgetId")
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Problem>().orFail(missing, throttled)
        }

        ep.errors.single { it.status == 429 }.headers shouldBe listOf(retryAfter, quota)
        ep.errors.single { it.status == 404 }.headers shouldBe emptyList()
        // `emits(...)` is the success response's list, and this endpoint has
        // nothing on it: a failure's header is not the endpoint's.
        ep.responseHeaders shouldBe emptyList()
    }
}
