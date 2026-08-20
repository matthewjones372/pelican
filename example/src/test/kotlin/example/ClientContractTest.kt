package example

import dev.pelican.Api
import dev.pelican.ApiError
import dev.pelican.In2
import dev.pelican.In3
import dev.pelican.In4
import dev.pelican.Outcome
import dev.pelican.Params
import dev.pelican.UploadedFile
import dev.pelican.div
import dev.pelican.endpoint
import dev.pelican.errorJson
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.orFail
import dev.pelican.pekko.PelicanServer
import dev.pelican.pekko.handledOrFail
import dev.pelican.pekko.start
import dev.pelican.test.ApiCallFailed
import dev.pelican.test.ApiClient
import dev.pelican.test.pekko.InMemoryTransport
import dev.pelican.test.pekko.client
import dev.pelican.test.pekko.inMemory
import dev.pelican.test.rawText
import dev.pelican.test.shouldBeFailure
import dev.pelican.test.shouldBeOk
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
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
        withClue(failure.response.body) { failure.response.body.contains("No user 999") shouldBe true }
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
        orders.all { it.userId == 1L } shouldBe true
        app.response(streamOrders, In4(1L, 7, null, null)).contentType shouldBe "application/x-ndjson"
    }

    @Test
    fun `an enum filter is applied`() {
        val orders = app.collect(streamOrders, In4(1L, 10, OrderStatus.SHIPPED, null))
        orders.size shouldBe 10
        orders.all { it.status == OrderStatus.SHIPPED } shouldBe true
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
        orders.all { it.userId == 1L } shouldBe true
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

    @Test
    fun `a stream can fail before its first element`() {
        val res = app.response(streamOrders, In4(9_999L, 5, null, null))
        res.status shouldBe 404
        res.contentType shouldBe "application/json"
        withClue(res.body) { res.body.contains("No user 9999") shouldBe true }
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
            In4(
                1L,
                "s-42",
                "March",
                UploadedFile("orders.csv", "text/csv", ByteArrayInputStream("1,anvil\n2,rope\n".toByteArray())),
            ),
        )

        result shouldBe ImportResult("March", "orders.csv", 2, "s-42")
    }

    @Test
    fun `an optional cookie the caller omits is null rather than empty`() {
        val res = app.transport.send(
            app.request(
                importOrders,
                In4(1L, "s-42", "March", UploadedFile("a.csv", "text/csv", ByteArrayInputStream("x\n".toByteArray()))),
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

    /**
     * `collect` flattens a stream, which is the wrong tool for asserting that
     * elements arrive *as they are produced*. The transport's escape hatch
     * hands back the response with its entity still unconsumed, so the same
     * back-pressure assertion a socket test makes works with no socket: the
     * source is throttled to one element per 100ms, and a buffered response
     * would deliver the first element at the same moment as the last.
     */
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
        withClue("stream finished suspiciously fast: ${totalMs}ms") { (totalMs >= 600) shouldBe true }
        withClue("first element at ${firstMs}ms of ${totalMs}ms — looks buffered, not streamed") {
            (firstMs < totalMs / 2) shouldBe true
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

/**
 * The one hole the types leave open, and what the server does about it.
 *
 * A handler must return a failure of the declared *type*, but two endpoints
 * declaring the same payload type can each hand out a failure the other never
 * listed. Nothing at compile time separates them, so the route checks it
 * rather than answering with an undocumented status.
 *
 * It is also the readiest example of an *unexpected* throwable, which makes it
 * the place to hold the line on what one of those tells a caller. The check's
 * own message names the endpoint and the declaration — useful in a log, and
 * nobody else's business — so the response carries a reference instead and the
 * throwable goes to the logger.
 */
class UndeclaredFailureTest {

    private val strayFailure = errorJson<ApiError>(410, "Declared somewhere else entirely")

    private val fetchUser = endpoint(userId) {
        get("users" / userId)
        json<User>() orFail noSuchUser
    }

    private fun apiThatReturnsAStrayFailure(
        exposeInternalErrors: Boolean = false,
        onServerError: ((String, dev.pelican.Endpoint<*, *>?, Throwable) -> Unit)? = null,
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
            withClue(body) { body.contains("never declared") shouldBe false }
            withClue(body) { body.contains("410") shouldBe false }
            withClue(body) { body.contains("Internal server error") shouldBe true }
            withClue(body) { body.contains("Reference: ") shouldBe true }
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
            withClue(message) { message.contains("never declared") shouldBe true }
        }
    }

    @Test
    fun `a local run can ask for the detail back`() {
        apiThatReturnsAStrayFailure(exposeInternalErrors = true).inMemory().use {
            val body = it.response(fetchUser, 1L).body
            withClue(body) { body.contains("never declared") shouldBe true }
        }
    }
}
