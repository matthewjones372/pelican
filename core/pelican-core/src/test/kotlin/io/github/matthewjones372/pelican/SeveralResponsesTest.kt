package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.successNamedBy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
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

        val out = ep.output as DeclaredResponses<*, *>
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

        (ep.output as DeclaredResponses<*, *>).successes.map { it.status } shouldBe listOf(201, 202, 204)
    }

    @Test
    fun `and chains the same way when the general overload has to take over`() {
        val ep = endpoint(newOrder) {
            post("orders")
            json<Order>(status = 200) or json<Order>(status = 201) or empty(status = 204)
        }

        val out = ep.output as DeclaredResponses<*, *>
        out.successes.map { it.status } shouldBe listOf(200, 201, 204)
        out.successes.none { it is DeclaredResponses<*, *> } shouldBe true
    }

    /** The failures survive the splice, whichever overload did the splicing. */
    @Test
    fun `a failure declared before the last alternative is still declared after it`() {
        val ep = endpoint(newOrder) {
            post("orders")
            (json<Order>(status = 200) orFail badKey) or json<Order>(status = 201) or empty(status = 204)
        }

        val out = ep.output as DeclaredResponses<*, *>
        out.successes.map { it.status } shouldBe listOf(200, 201, 204)
        out.failures.map { it.status } shouldBe listOf(401)
    }

    @Test
    fun `failures are declared beside them, and the endpoint documents both`() {
        val ep = endpoint(newOrder) {
            post("orders")
            created or accepted orFail badKey
        }

        val out = ep.output as DeclaredResponses<*, *>
        out.successes.map { it.status } shouldBe listOf(201, 202)
        out.failures.map { it.status } shouldBe listOf(401)
        ep.errors.map { it.status } shouldBe listOf(401)
    }

    @Test
    fun `two responses carrying one type stay distinguishable by which was named`() {
        val found = json<Order>(status = 200)
        val made = json<Order>(status = 201)

        val ok = found(Order(1)) as Outcome.Ok
        ok.declared shouldBeSameInstanceAs found
        (made(Order(1)) as Outcome.Ok).declared shouldBeSameInstanceAs made

        // Equal payloads, different responses.
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

    @Test
    fun `a header that arrived but does not decode reads as null, on a success as on a failure`() {
        val count = responseHeader<Int>("X-Count")
        val listed = json<Order>(status = 200, count)

        val answer = Outcome.Ok(Order(1), listed, listOf("X-Count" to "many"))

        answer[count].shouldBeNull()
        answer.value shouldBe Order(1)
    }

    @Test
    fun `a header on an endpoint's only response is refused when the endpoint is built`() {
        val refused = shouldThrow<IllegalStateException> { placing(json<Order>(status = 201, location)) }
        refused.message shouldContain "Declare them with emits(...)"
    }

    // -------------------------------------------- which response ok() means

    private val twoWays = created or accepted

    @Test
    fun `a named response resolves to itself, and its headers travel with it`() {
        val answer = created(Order(7), location of "/orders/7") as Outcome.Ok

        twoWays.successNamedBy(answer) shouldBeSameInstanceAs created
        answer.headers shouldBe listOf("Location" to "/orders/7")
    }

    @Test
    fun `a bare ok resolves to the first, which is what it has always meant`() {
        val single = json<Order>(status = 200) or accepted

        single.successNamedBy(ok(Order(1)) as Outcome.Ok) shouldBeSameInstanceAs single.successes.first()
    }

    @Test
    fun `a bare ok is refused where the response it means promises a header`() {
        val refused = shouldThrow<IllegalStateException> { twoWays.successNamedBy(ok(Order(1)) as Outcome.Ok) }

        withClue(refused.message) {
            refused.message shouldContain "ok(...)"
            refused.message shouldContain "Location"
            refused.message shouldContain "json:201"
        }
    }

    @Test
    fun `an optional header on the first success leaves a bare ok alone`() {
        val cursor = responseHeader<String>("X-Cursor").optional()
        val paged = json<Order>(status = 200, cursor) or accepted

        paged.successNamedBy(ok(Order(1)) as Outcome.Ok) shouldBeSameInstanceAs paged.successes.first()
    }

    @Test
    fun `a single declared success still takes a bare ok`() {
        val only = json<Order>(status = 200) orFail badKey

        only.successNamedBy(ok(Order(1)) as Outcome.Ok) shouldBeSameInstanceAs only.successes.first()
    }

    @Test
    fun `a response the output never declared is refused wherever it came from`() {
        val elsewhere = json<Order>(status = 203)

        // Its own type, so a handler's bookkeeping mistake is distinguishable
        // from any other throwable by whatever is watching `onServerError`.
        shouldThrow<UndeclaredResponse> { twoWays.successNamedBy(elsewhere(Order(1)) as Outcome.Ok) }
            .message shouldContain "json:203"
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

        val out = ep.output as DeclaredResponses<*, *>
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
