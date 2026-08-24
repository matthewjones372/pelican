package dev.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * `or`, which is `orFail` with the word "fail" taken out of it.
 *
 * The two are one mechanism: an output holds the responses an endpoint
 * declares, a handler produces one by naming it, and which one it named is
 * what fixes the status. Declared failures were the first users of that, and
 * these are the tests for the half that is not about failing.
 *
 * What cannot be tested here is the part that matters most — that a handler
 * which answers with something the endpoint never declared does not compile.
 * That is a property of the binders in the three backend modules, and the
 * evidence for it is that `:example` compiles at all.
 */
class SeveralResponsesTest {

    data class Order(val id: Long)
    data class Queued(val ticket: String)
    data class Problem(val code: String)

    private val newOrder = jsonBody<Order>()
    private val location = responseHeader<String>("Location", "Where the new order lives")

    private val created = json<Order>(status = 201, location)
    private val accepted = json<Queued>(status = 202)
    private val badKey = errorJson<Problem>(401, "Bad API key")

    private fun placing(output: Output<Order>) = endpoint(newOrder) {
        post("orders")
        output
    }

    @Test
    fun `two successes are two declared responses, in the order they were written`() {
        val ep = endpoint(newOrder) {
            post("orders")
            created or accepted
        }

        val out = ep.output as FallibleOutput<*, *>
        out.successes.map { it.status } shouldBe listOf(201, 202)
        out.failures.shouldBeEmpty()

        // The first is what the endpoint reports as *its* status, media type
        // and payload — which is what every reading that predates this saw.
        ep.output.status shouldBe 201
        ep.output.payloadType shouldBe kotlin.reflect.typeOf<Order>()
    }

    @Test
    fun `a third alternative chains rather than nests`() {
        val ep = endpoint(newOrder) {
            post("orders")
            created or accepted or empty(status = 204)
        }

        (ep.output as FallibleOutput<*, *>).successes.map { it.status } shouldBe listOf(201, 202, 204)
    }

    /**
     * The chain where the payload types agree, and the one place this could
     * have gone quietly wrong: `json<Order>(200) or json<Order>(201)` is an
     * `Output<Order>`, so the third `or` finds a receiver whose payload type is
     * *narrower* than `empty()`'s and falls through to the general overload.
     * Splicing is what keeps the two readings the same list rather than leaving
     * a pair nested inside a response nothing could render.
     */
    @Test
    fun `and chains the same way when the general overload has to take over`() {
        val ep = endpoint(newOrder) {
            post("orders")
            json<Order>(status = 200) or json<Order>(status = 201) or empty(status = 204)
        }

        val out = ep.output as FallibleOutput<*, *>
        out.successes.map { it.status } shouldBe listOf(200, 201, 204)
        out.successes.none { it is FallibleOutput<*, *> } shouldBe true
    }

    /** The failures survive the splice, whichever overload did the splicing. */
    @Test
    fun `a failure declared before the last alternative is still declared after it`() {
        val ep = endpoint(newOrder) {
            post("orders")
            (json<Order>(status = 200) orFail badKey) or json<Order>(status = 201) or empty(status = 204)
        }

        val out = ep.output as FallibleOutput<*, *>
        out.successes.map { it.status } shouldBe listOf(200, 201, 204)
        out.failures.map { it.status } shouldBe listOf(401)
    }

    @Test
    fun `failures are declared beside them, and the endpoint documents both`() {
        val ep = endpoint(newOrder) {
            post("orders")
            created or accepted orFail badKey
        }

        val out = ep.output as FallibleOutput<*, *>
        out.successes.map { it.status } shouldBe listOf(201, 202)
        out.failures.map { it.status } shouldBe listOf(401)
        ep.errors.map { it.status } shouldBe listOf(401)
    }

    /**
     * The case a payload type cannot settle. `200 Order` and `201 Order` carry
     * the same bytes, so identity is what tells them apart — the same answer
     * [ErrorOutput] gives for two failures sharing a type.
     */
    @Test
    fun `two responses carrying one type stay distinguishable by which was named`() {
        val found = json<Order>(status = 200)
        val made = json<Order>(status = 201)

        val ok = found(Order(1)) as Outcome.Ok
        ok.declared shouldBeSameInstanceAs found
        (made(Order(1)) as Outcome.Ok).declared shouldBeSameInstanceAs made

        // Equal payloads, different responses — which is the whole point.
        ok.value shouldBe (made(Order(1)) as Outcome.Ok).value
    }

    @Test
    fun `a bare ok names none, and means the first`() {
        val answer: Outcome<Nothing, Order> = ok(Order(1))
        (answer as Outcome.Ok).declared shouldBe null
    }

    @Test
    fun `a response with no body is named without one`() {
        val queued = empty(status = 202)
        val answer = queued() as Outcome.Ok

        answer.declared shouldBeSameInstanceAs queued
        answer.value shouldBe Unit
    }

    // ------------------------------------------------------------ headers

    @Test
    fun `a header declared on one response travels with it`() {
        val answer = created(Order(7), location of "/orders/7") as Outcome.Ok

        answer.headers shouldBe listOf("Location" to "/orders/7")
        answer[location] shouldBe "/orders/7"
    }

    @Test
    fun `a header this response never declared is refused where it is sent`() {
        val refused = shouldThrow<IllegalStateException> {
            accepted(Queued("t-1"), location of "/orders/7")
        }
        refused.message shouldContain "Location was sent with json:202, which never declared it"
    }

    @Test
    fun `a required header left out is refused where it is sent`() {
        val refused = shouldThrow<IllegalStateException> { created(Order(7)) }
        refused.message shouldContain "json:201 declares Location and this call left it out"
    }

    @Test
    fun `an optional header may simply be left off`() {
        val cursor = responseHeader<String>("X-Cursor").optional()
        val page = json<Order>(status = 200, cursor)

        (page(Order(1)) as Outcome.Ok).headers.shouldBeEmpty()
    }

    /**
     * The reading end, where an [Outcome] is built from a response rather than
     * by a handler: a header that arrived unreadable is as absent as one that
     * never arrived, and neither is a reason to lose the response it came on.
     */
    @Test
    fun `a header that arrived but does not decode reads as null, on a success as on a failure`() {
        val count = responseHeader<Int>("X-Count")
        val listed = json<Order>(status = 200, count)

        val answer = Outcome.Ok(Order(1), listed, listOf("X-Count" to "many"))

        answer[count].shouldBeNull()
        answer.value shouldBe Order(1)
    }

    /**
     * Declared on an endpoint's only response, a header is a promise nothing
     * could keep: the handler for one response returns the payload alone and
     * never sees the declaration.
     */
    @Test
    fun `a header on an endpoint's only response is refused when the endpoint is built`() {
        val refused = shouldThrow<IllegalStateException> { placing(json<Order>(status = 201, location)) }
        refused.message shouldContain "Declare them with emits(...)"
    }

    // ------------------------------------------------------------ what is refused

    @Test
    fun `two responses cannot share a status`() {
        val clash = shouldThrow<IllegalArgumentException> { json<Order>(200) or json<Queued>(200) }
        clash.message shouldContain "Two responses are declared for status 200"
    }

    @Test
    fun `a success cannot share a status with a declared failure either`() {
        val clash = shouldThrow<IllegalArgumentException> {
            json<Order>(200) or json<Queued>(202) orFail errorJson<Problem>(202, "Also queued")
        }
        clash.message shouldContain "Two responses are declared for status 202"
    }

    @Test
    fun `a streamed response cannot be one alternative among several`() {
        val refused = shouldThrow<IllegalArgumentException> {
            endpoint(newOrder) {
                post("orders")
                ndjson<Order>(status = 200) or empty(status = 202)
            }
        }
        refused.message shouldContain "ndjson:200 streams"
        refused.message shouldContain "Declare the stream as the one success"
    }

    /** A stream is still a success; it is just the only one. */
    @Test
    fun `a stream with failures beside it is unchanged`() {
        val ep = endpoint(newOrder) {
            post("orders")
            ndjson<Order>() orFail badKey
        }

        val out = ep.output as FallibleOutput<*, *>
        out.successes.single().mediaType shouldBe "application/x-ndjson"
        out.failures.single().status shouldBe 401
    }

    @Test
    fun `orFail still refuses to stack`() {
        shouldThrow<IllegalArgumentException> {
            (json<Order>() orFail badKey) orFail errorJson<Problem>(403, "Not yours")
        }
    }
}
