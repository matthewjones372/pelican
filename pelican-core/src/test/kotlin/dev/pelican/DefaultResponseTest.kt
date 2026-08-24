package dev.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf

/**
 * `default`: the one response an endpoint describes and cannot produce.
 *
 * Everything else an endpoint declares is a status — one a handler returns, or
 * one it throws. This is the absence of a status, so the tests below are mostly
 * about what it is *not*: not an `ErrorOutput`, not something `orFail` can be
 * given, not something a binder ever sees. What it is, is a line in the
 * document saying what the statuses nobody enumerated will look like.
 */
class DefaultResponseTest {

    data class Problem(val code: String)
    data class Widget(val id: Long)

    private val widgetId = pathParam<Long>("widgetId")
    private val retryAfter = responseHeader<Long>("Retry-After")

    @Test
    fun `a default is documented under no status at all`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            defaultResponse("Any other failure")
            json<Widget>()
        }

        val declared = ep.errors.single()
        declared.status.shouldBeNull()
        declared.description shouldBe "Any other failure"
        declared.type.shouldBeNull()
    }

    /**
     * The payload is the half worth keeping. A document that says "and any
     * other error is a Problem" is saying something about every status it did
     * not list, and dropping the schema would leave only the shrug.
     */
    @Test
    fun `a default carrying a payload publishes its type and stays unreturnable`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            defaultJson<Problem>("Any other failure", retryAfter)
            json<Widget>()
        }

        val declared = ep.errors.single()
        declared.status.shouldBeNull()
        declared.type shouldBe typeOf<Problem>()
        declared.headers shouldBe listOf(retryAfter)

        // Not a `FallibleOutput`: nothing was added to the output, so the
        // handler for this endpoint returns a Widget and has nothing to name.
        (ep.output is FallibleOutput<*, *>) shouldBe false
    }

    @Test
    fun `a default sits beside the failures a handler does return`() {
        val missing = errorJson<Problem>(404, "No widget with that id")
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            defaultJson<Problem>("Any other failure")
            json<Widget>() orFail missing
        }

        ep.errors.map { it.status } shouldBe listOf(null, 404)
        (ep.output as FallibleOutput<*, *>).failures.map { it.status } shouldBe listOf(404)
    }

    /**
     * A document has one `default` key. A second declaration would not be
     * published beside the first, it would replace it — so the endpoint would
     * say something nobody wrote.
     */
    @Test
    fun `two defaults are refused, because a document has room for one`() {
        shouldThrow<IllegalStateException> {
            endpoint(widgetId) {
                get("widgets" / widgetId)
                defaultResponse("Any other failure")
                defaultJson<Problem>("Or this one")
                json<Widget>()
            }
        }.message.orEmpty() shouldContain "more than one default response"
    }

    @Test
    fun `an endpoint that declares none has none`() {
        val ep = endpoint(widgetId) {
            get("widgets" / widgetId)
            json<Widget>()
        }

        ep.errors.shouldBeEmpty()
    }
}
