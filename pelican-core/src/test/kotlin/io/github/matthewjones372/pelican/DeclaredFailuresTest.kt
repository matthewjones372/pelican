package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.failureNamedBy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * The bookkeeping behind `orFail`: a failure listed on an output is part of
 * the endpoint's type *and* part of its document, whether it was declared
 * inside the block or as a value shared between endpoints.
 *
 * What cannot be tested here is the part that matters most — that a handler
 * returning an undeclared failure does not compile. That is a property of the
 * binders in `pelican-pekko`, and the evidence for it is that the example
 * compiles at all.
 */
class DeclaredFailuresTest {

    data class Problem(val code: String)
    data class Widget(val id: Long)

    private val widgetId = pathParam<Long>("widgetId")

    private val missing = errorJson<Problem>(404, "No widget with that id")
    private val forbidden = errorJson<Problem>(403, "Not yours")

    @Test
    fun `a failure declared as a value is documented once`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>() orFail missing
        }

        ep.errors.size shouldBe 1
        ep.errors.single().status shouldBe 404
        ep.errors.single().description shouldBe "No widget with that id"
        ep.errors.single().type shouldBe typeOfProblem()
    }

    @Test
    fun `a failure declared inside the block is documented once, not twice`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>() orFail errorJson<Problem>(404, "No widget with that id")
        }

        ep.errors.size shouldBe 1
    }

    @Test
    fun `several failures keep their own statuses`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>().orFail(missing, forbidden)
        }

        ep.errors.map { it.status } shouldBe listOf(404, 403)
    }

    @Test
    fun `a failure documented but not declared stays documentation only`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            errorJson<Problem>(404, "No widget with that id")
            json<Widget>()
        }

        // Documented, so the spec is unchanged from before `orFail` existed —
        // but the output is a plain JsonOutput, so the handler is the total
        // one and the 404 is still whatever the handler throws.
        ep.errors.size shouldBe 1
        ep.output.shouldBeInstanceOf<JsonOutput<*>>()
    }

    @Test
    fun `two failures cannot share a status on one output`() {
        val clash = shouldThrow<IllegalArgumentException> {
            endpoint(widgetId) {
                get("widgets" / widgetId)
                json<Widget>().orFail(missing, errorJson<Problem>(404, "Also not found"))
            }
        }
        clash.message shouldContain "404"
    }

    @Test
    fun `orFail does not stack`() {
        shouldThrow<IllegalArgumentException> {
            endpoint(widgetId) {
                get("widgets" / widgetId)
                (json<Widget>() orFail missing) orFail forbidden
            }
        }
    }

    @Test
    fun `the wrapped output still drives status, media type and payload`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>(status = 201) orFail missing
        }

        ep.output.status shouldBe 201
        ep.output.mediaType shouldBe "application/json"
        ep.output.payloadType shouldBe typeOfWidget()
    }

    @Test
    fun `the failure names itself, so the same type can carry two statuses`() {
        val (declared, error) = missing(Problem("gone")) as Outcome.Err
        declared shouldBeSameInstanceAs missing
        error shouldBe Problem("gone")
        (forbidden(Problem("gone")) as Outcome.Err).declared?.status shouldBe 403
    }

    sealed interface Trouble {
        data class Missing(val id: Long) : Trouble
        data class Denied(val who: String) : Trouble
    }

    @Test
    fun `several payload types infer to their common supertype`() {
        val gone = errorJson<Trouble.Missing>(404, "No widget with that id")
        val denied = errorJson<Trouble.Denied>(403, "Not yours")

        // The declared type on the left is the assertion: inference that
        // stopped producing the sealed supertype would leave a handler unable
        // to answer with a `when` over Trouble.
        val ep: Endpoint<Long, Outcome<Trouble, Widget>> = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>().orFail(gone, denied)
        }

        ep.errors.map { it.status } shouldBe listOf(404, 403)

        val answer: Outcome<Trouble, Widget> = gone(Trouble.Missing(7))
        (answer as Outcome.Err).error shouldBe Trouble.Missing(7)
    }

    // ---------------------------------------------------- err, the bare form

    @Test
    fun `err means the single declared failure, as ok means the first success`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>() orFail missing
        }

        val out = ep.output as DeclaredResponses<*, *>
        out.failureNamedBy(Outcome.Err(null, Problem("gone"))).shouldBeSameInstanceAs(missing)
        ep.statusFor(err(Problem("gone")), error = null) shouldBe 404
    }

    @Test
    fun `err with several declared failures is refused, naming them`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>().orFail(missing, forbidden)
        }

        val out = ep.output as DeclaredResponses<*, *>
        shouldThrow<UndeclaredResponse> {
            out.failureNamedBy(Outcome.Err(null, Problem("gone")))
        }.message.orEmpty() shouldContain "names no failure"

        // The filter-facing reader cannot throw; the response it mirrors is the 500.
        ep.statusFor(err(Problem("gone")), error = null) shouldBe 500
    }

    @Test
    fun `err cannot stand in for a failure that promised a header`() {
        val retryAfter = responseHeader<Long>("Retry-After", "Seconds to wait")
        val throttled = errorJson<Problem>(429, "Too many requests", retryAfter)
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>() orFail throttled
        }

        val out = ep.output as DeclaredResponses<*, *>
        shouldThrow<IllegalStateException> {
            out.failureNamedBy(Outcome.Err(null, Problem("slow")))
        }.message.orEmpty() shouldContain "Retry-After"
    }

    @Test
    fun `err still refuses a payload the single failure does not carry`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>() orFail missing
        }

        val out = ep.output as DeclaredResponses<*, *>
        shouldThrow<UndeclaredResponse> {
            out.failureNamedBy(Outcome.Err(null, Widget(1)))
        }.message.orEmpty() shouldContain "carries"
    }

    private fun typeOfProblem() = kotlin.reflect.typeOf<Problem>()
    private fun typeOfWidget() = kotlin.reflect.typeOf<Widget>()
}
