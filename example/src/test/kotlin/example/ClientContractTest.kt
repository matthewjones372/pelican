package example

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.In4
import io.github.matthewjones372.pelican.In5
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.UploadedFile
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.handledOrFail
import io.github.matthewjones372.pelican.pekko.start
import io.github.matthewjones372.pelican.test.ApiCallFailed
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.pekko.InMemoryTransport
import io.github.matthewjones372.pelican.test.pekko.client
import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.github.matthewjones372.pelican.test.rawText
import io.github.matthewjones372.pelican.test.shouldBeFailure
import io.github.matthewjones372.pelican.test.shouldBeOk
import io.github.matthewjones372.pelican.test.shouldBeResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.inspectors.forAll
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.stream.Materializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream

/**
 * One suite, two transports.
 *
 * Every assertion below is written against [ApiClient], which knows nothing
 * about how the request travels. [InMemoryContractTest] runs them straight
 * through the interpreted route; [OverHttpContractTest] runs the same ones
 * against a bound server on a real port. A difference between the two is a
 * real difference in behaviour, not a difference in the test.
 *
 * Note what is *not* here: no path strings, no query strings, no hand-written
 * JSON. Requests are built from the same endpoint values the server is
 * interpreted from and the OpenAPI document is generated from, so renaming a
 * parameter breaks this file at compile time.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class ClientContractTest {

    protected lateinit var app: ApiClient

    protected abstract fun open(): ApiClient
    protected open fun shutDown() = app.close()

    @BeforeAll fun setUp() { app = open() }

    @AfterAll fun tearDown() = shutDown()

    // ------------------------------------------------------------ plain json

    @Test
    fun `returns a typed user`() {
        val user: User = app.call(getUser, 1L)
        user shouldBe User(1, "Ada Lovelace", "ada@example.com")
    }

    @Test
    fun `a handler's notFound surfaces as a failed call`() {
        val failure = shouldThrow<ApiCallFailed> { app.call(getUser, 999L) }
        failure.response.status shouldBe 404
        failure.response.body shouldContain "No user 999"
    }

    @Test
    fun `the error path can be asserted on without an exception`() {
        val res = app.response(getUser, 999L)
        res.status shouldBe 404
        res.contentType shouldBe "application/json"
    }

    // ------------------------------------------------------------ streaming

    @Test
    fun `collects ndjson into typed elements`() {
        val orders: List<Order> = app.collect(streamOrders, In4(1L, 7, null, null))
        orders.size shouldBe 7
        orders.forAll { it.userId shouldBe 1L }
        app.response(streamOrders, In4(1L, 7, null, null)).contentType shouldBe "application/x-ndjson"
    }

    @Test
    fun `an enum filter is applied`() {
        val orders = app.collect(streamOrders, In4(1L, 10, OrderStatus.SHIPPED, null))
        orders.size shouldBe 10
        orders.forAll { it.status shouldBe OrderStatus.SHIPPED }
    }

    @Test
    fun `the server applies its own default when a parameter is omitted`() {
        // The tuple form always supplies `limit`, so drop it from the built
        // request to exercise the default the endpoint declares.
        val req = app.request(streamOrders, In4(1L, 7, null, null)).withoutQuery("limit")
        val body = app.transport.send(req).body
        body.trim().lines().size shouldBe 25
    }

    @Test
    fun `collects a streamed json array`() {
        val orders: List<Order> = app.collect(listOrders, In2(1L, 3))
        orders.size shouldBe 3
        orders.forAll { it.userId shouldBe 1L }
    }

    @Test
    fun `collects server-sent events`() {
        val ticks: List<Tick> = app.collect(watchOrders, In2(1L, 3))
        ticks.map { it.seq } shouldBe listOf(1, 2, 3)
        app.response(watchOrders, In2(1L, 3)).contentType shouldBe "text/event-stream"
    }

    // ------------------------------------------------------------ bodies

    @Test
    fun `posts a typed body and gets the declared 201`() {
        val order = app.call(placeOrder, In3(1L, "let-me-in", CreateOrder("anvil", 3)))
        order.item shouldBe "anvil"
        order.quantity shouldBe 3
        order.status shouldBe OrderStatus.PENDING
        app.response(placeOrder, In3(1L, "let-me-in", CreateOrder("anvil", 3))).status shouldBe 201
    }

    @Test
    fun `a body field's default is applied by the codec`() {
        val order = app.call(placeOrder, In3(1L, "let-me-in", CreateOrder("rope")))
        order.quantity shouldBe 1
    }

    @Test
    fun `a union body arrives as the branch its discriminator names`() {
        val receipt = app.call(payOrder, In4(1L, 7L, "let-me-in", BankTransfer("GB33")))
        receipt.orderId shouldBe 7L
        receipt.paidWith shouldBe BankTransfer("GB33")

        val byCard = app.call(payOrder, In4(1L, 7L, "let-me-in", Card("4111", "12/28")))
        withClue("the branch decides the surcharge, and the handler matched on it") {
            byCard.total shouldBe receipt.total + 1.5
        }
    }

    @Test
    fun `the union comes back nested in a payload, carrying the value that selects it`() {
        val body = app.response(payOrder, In4(1L, 7L, "let-me-in", Card("4111", "12/28"))).body

        withClue("the wire name is the annotation's, not the class's") {
            body shouldContain """"method":"card""""
        }
        body shouldNotContain "Card"
    }

    @Test
    fun `a bad api key is 401`() {
        app.response(placeOrder, In3(1L, "wrong", CreateOrder("anvil", 1))).status shouldBe 401
    }

    // ------------------------------------------------------ declared failures
    //
    // placeOrder declares two, both carrying ApiError. The handler names which
    // one it is returning, so the status comes from the declaration rather
    // than from the payload's type — and the client reads them back the same
    // way, as an Outcome rather than a status code to interpret.

    @Test
    fun `each declared failure comes back as itself`() {
        // Both failures carry ApiError, so equality on the payload cannot say
        // which one this is — `shouldBeFailure` asserts on the declaration the
        // handler named, which is what fixes the status.
        val badKey = app.outcome(placeOrder, In3(1L, "wrong", CreateOrder("anvil")))
        badKey shouldBeFailure badApiKey shouldBe ApiError(401, "Bad API key")

        app.outcome(placeOrder, In3(9_999L, "let-me-in", CreateOrder("anvil"))) shouldBeFailure noSuchUser
        noSuchUser.status shouldBe 404
    }

    @Test
    fun `the success side of a fallible endpoint is unchanged`() {
        val placed = app.outcome(placeOrder, In3(1L, "let-me-in", CreateOrder("rope"))).shouldBeOk()
        placed.item shouldBe "rope"
        app.response(placeOrder, In3(1L, "let-me-in", CreateOrder("rope"))).status shouldBe 201
    }

    // ------------------------------------------------ more than one success
    //
    // submitOrder declares two, carrying different payload types, with a
    // failure beside them. The handler names the response it is producing, so
    // the status comes from the declaration exactly as it does on the failure
    // side — and the client reads it back the same way.

    @Test
    fun `each declared success comes back as itself, under its own status`() {
        val placed = app.outcome(submitOrder, In3(1L, "let-me-in", CreateOrder("anvil", 3)))
        placed shouldBeResponse orderPlaced
        app.response(submitOrder, In3(1L, "let-me-in", CreateOrder("anvil", 3))).status shouldBe 201

        val queued = app.outcome(submitOrder, In3(1L, "let-me-in", CreateOrder("anvil", 5_000)))
        queued shouldBeResponse orderQueued shouldBe Queued("ticket-1-anvil", position = 5_000)
        app.response(submitOrder, In3(1L, "let-me-in", CreateOrder("anvil", 5_000))).status shouldBe 202
    }

    /** The `Location` belongs to the 201 and to nothing else, `emits(...)` not being involved. */
    @Test
    fun `the header declared on one success is absent from the other`() {
        app.response(submitOrder, In3(1L, "let-me-in", CreateOrder("anvil")))
            .header("Location").shouldNotBeNull() shouldContain "/users/1/orders/"

        app.response(submitOrder, In3(1L, "let-me-in", CreateOrder("anvil", 5_000)))
            .header("Location") shouldBe null
    }

    @Test
    fun `a failure declared beside two successes is still a failure`() {
        app.outcome(submitOrder, In3(1L, "wrong", CreateOrder("anvil"))) shouldBeFailure badApiKey
    }

    @Test
    fun `a stream can fail before its first element`() {
        val res = app.response(streamOrders, In4(9_999L, 5, null, null))
        res.status shouldBe 404
        res.contentType shouldBe "application/json"
        res.body shouldContain "No user 9999"
    }

    @Test
    fun `an empty output is 204 with no body`() {
        val res = app.response(cancelOrder, In3(1L, 5L, "let-me-in"))
        res.status shouldBe 204
        res.body shouldBe ""
    }

    @Test
    fun `a raw body is echoed back`() {
        val payload = "x".repeat(50_000)
        val res = app.response(echo, rawText(payload))
        res.status shouldBe 200
        res.body shouldBe payload
    }

    // -------------------------------------------------- forms and uploads

    @Test
    fun `a form body is posted as a form and read back as the declared types`() {
        val order = app.call(placeOrderForm, In2(1L, CreateOrder("anvil", quantity = 2)))

        order.item shouldBe "anvil"
        order.quantity shouldBe 2
    }

    @Test
    fun `a multipart upload arrives with its text part, its file and its cookie`() {
        val result = app.call(
            importOrders,
            In5(
                1L,
                "s-42",
                "March",
                UploadedFile("manifest.csv", "text/csv", ByteArrayInputStream("2 orders".toByteArray())),
                UploadedFile("orders.csv", "text/csv", ByteArrayInputStream("1,anvil\n2,rope\n".toByteArray())),
            ),
        )

        result shouldBe ImportResult("March", "orders.csv", 2, "s-42", "2 orders")
    }

    @Test
    fun `an optional cookie the caller omits is null rather than empty`() {
        val res = app.transport.send(
            app.request(
                importOrders,
                In5(
                    1L,
                    "s-42",
                    "March",
                    UploadedFile("m.csv", "text/csv", ByteArrayInputStream("one".toByteArray())),
                    UploadedFile("a.csv", "text/csv", ByteArrayInputStream("x\n".toByteArray())),
                ),
            ).withoutHeader("Cookie"),
        )

        res.body shouldContain "\"session\":null"
    }

    // ------------------------------------------------------------ lens style

    @Test
    fun `a lens-style endpoint is called with the params bag`() {
        val orders = app.collect(
            searchOrders,
            Params(mapOf(limit to 4, statusFilter to null, traceId to "abc"), null),
        )
        orders.size shouldBe 4
    }
}

/** No socket, no port: requests go straight through the interpreted route. */
class InMemoryContractTest : ClientContractTest() {
    override fun open(): ApiClient = ordersApi().inMemory("orders-in-memory")

    @Test
    fun `elements are delivered as produced, not buffered until the end`() {
        val transport = app.transport as InMemoryTransport
        val materializer = Materializer.createMaterializer(transport.system)

        val res = transport.exchange(HttpRequest.GET("/users/1/orders/watch?limit=8"))
        res.status().intValue() shouldBe 200

        val start = System.nanoTime()
        var firstAt = -1L
        var chunks = 0
        res.entity().dataBytes
            .runForeach(
                {
                    if (firstAt < 0) firstAt = System.nanoTime()
                    chunks++
                },
                materializer,
            )
            .toCompletableFuture()
            .join()

        val totalMs = (System.nanoTime() - start) / 1_000_000
        val firstMs = (firstAt - start) / 1_000_000

        chunks shouldBe 8
        withClue("stream finished suspiciously fast: ${totalMs}ms") { totalMs shouldBeGreaterThanOrEqualTo 600 }
        withClue("first element at ${firstMs}ms of ${totalMs}ms — looks buffered, not streamed") {
            firstMs shouldBeLessThan totalMs / 2
        }
    }
}

/** The same assertions, over a real connection to a bound server. */
class OverHttpContractTest : ClientContractTest() {
    private lateinit var server: PelicanServer

    override fun open(): ApiClient {
        server = ordersApi().start(port = 0, systemName = "orders-over-http")
        return server.client()
    }

    override fun shutDown() {
        app.close()
        server.stop().toCompletableFuture().join()
    }
}

class UndeclaredFailureTest {

    private val strayFailure = errorJson<ApiError>(410, "Declared somewhere else entirely")

    private val fetchUser = endpoint(userId) {
        get("users" / userId)
        json<User>() orFail noSuchUser
    }

    private fun apiThatReturnsAStrayFailure(
        exposeInternalErrors: Boolean = false,
        onServerError: ((String, io.github.matthewjones372.pelican.Endpoint<*, *>?, Throwable) -> Unit)? = null,
    ) = Api(
        endpoints = listOf(fetchUser handledOrFail { strayFailure(ApiError(410, "nope")) }),
        codecs = JacksonCodecs,
        exposeInternalErrors = exposeInternalErrors,
        onServerError = onServerError,
    )

    @Test
    fun `returning a failure this endpoint never declared is a 500, not a 410`() {
        apiThatReturnsAStrayFailure().inMemory().use {
            it.response(fetchUser, 1L).status shouldBe 500
        }
    }

    @Test
    fun `the 500 body says nothing about what went wrong inside`() {
        apiThatReturnsAStrayFailure().inMemory().use {
            val body = it.response(fetchUser, 1L).body
            // The internal message — the one naming the endpoint and the stray
            // declaration — is what a caller must not be handed.
            body shouldNotContain "never declared"
            body shouldNotContain "error:410"
            body shouldContain "Internal server error"
            // The whole detail, not a substring of it: a bare `shouldNotContain
            // "410"` also failed whenever the random reference happened to
            // contain those three characters, which is one run in about 450.
            body shouldContain Regex("\"detail\":\"Reference: [0-9a-f]{12}\"")
        }
    }

    @Test
    fun `the reference in the body is what the server logs against`() {
        val logged = mutableListOf<Triple<String, String?, String>>()
        val api = apiThatReturnsAStrayFailure(
            onServerError = { ref, ep, t -> logged += Triple(ref, ep?.toString(), t.message.orEmpty()) },
        )

        api.inMemory().use {
            val body = it.response(fetchUser, 1L).body
            val reference = Regex("""Reference: ([0-9a-f]+)""").find(body)?.groupValues?.get(1)
            withClue("no reference in $body") { reference.shouldNotBeNull() }

            val (loggedRef, endpoint, message) = logged.single()
            loggedRef shouldBe reference
            // What the caller was denied is exactly what the log gets.
            endpoint shouldBe "GET /users/{userId}"
            message shouldContain "never declared"
        }
    }

    @Test
    fun `a local run can ask for the detail back`() {
        apiThatReturnsAStrayFailure(exposeInternalErrors = true).inMemory().use {
            val body = it.response(fetchUser, 1L).body
            body shouldContain "never declared"
        }
    }
}
