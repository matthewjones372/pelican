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
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.stream.Materializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
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
        assertEquals(User(1, "Ada Lovelace", "ada@example.com"), user)
    }

    @Test
    fun `a handler's notFound surfaces as a failed call`() {
        val failure = assertThrows<ApiCallFailed> { app.call(getUser, 999L) }
        assertEquals(404, failure.response.status)
        assertTrue(failure.response.body.contains("No user 999"), failure.response.body)
    }

    @Test
    fun `the error path can be asserted on without an exception`() {
        val res = app.response(getUser, 999L)
        assertEquals(404, res.status)
        assertEquals("application/json", res.contentType)
    }

    // ------------------------------------------------------------ streaming

    @Test
    fun `collects ndjson into typed elements`() {
        val orders: List<Order> = app.collect(streamOrders, In4(1L, 7, null, null))
        assertEquals(7, orders.size)
        assertTrue(orders.all { it.userId == 1L })
        assertEquals("application/x-ndjson", app.response(streamOrders, In4(1L, 7, null, null)).contentType)
    }

    @Test
    fun `an enum filter is applied`() {
        val orders = app.collect(streamOrders, In4(1L, 10, OrderStatus.SHIPPED, null))
        assertEquals(10, orders.size)
        assertTrue(orders.all { it.status == OrderStatus.SHIPPED })
    }

    @Test
    fun `the server applies its own default when a parameter is omitted`() {
        // The tuple form always supplies `limit`, so drop it from the built
        // request to exercise the default the endpoint declares.
        val req = app.request(streamOrders, In4(1L, 7, null, null)).withoutQuery("limit")
        val body = app.transport.send(req).body
        assertEquals(25, body.trim().lines().size)
    }

    @Test
    fun `collects a streamed json array`() {
        val orders: List<Order> = app.collect(listOrders, In2(1L, 3))
        assertEquals(3, orders.size)
        assertTrue(orders.all { it.userId == 1L })
    }

    @Test
    fun `collects server-sent events`() {
        val ticks: List<Tick> = app.collect(watchOrders, In2(1L, 3))
        assertEquals(listOf(1, 2, 3), ticks.map { it.seq })
        assertEquals("text/event-stream", app.response(watchOrders, In2(1L, 3)).contentType)
    }

    // ------------------------------------------------------------ bodies

    @Test
    fun `posts a typed body and gets the declared 201`() {
        val order = app.call(placeOrder, In3(1L, "let-me-in", CreateOrder("anvil", 3)))
        assertEquals("anvil", order.item)
        assertEquals(3, order.quantity)
        assertEquals(OrderStatus.PENDING, order.status)
        assertEquals(201, app.response(placeOrder, In3(1L, "let-me-in", CreateOrder("anvil", 3))).status)
    }

    @Test
    fun `a body field's default is applied by the codec`() {
        val order = app.call(placeOrder, In3(1L, "let-me-in", CreateOrder("rope")))
        assertEquals(1, order.quantity)
    }

    @Test
    fun `a bad api key is 401`() {
        assertEquals(401, app.response(placeOrder, In3(1L, "wrong", CreateOrder("anvil", 1))).status)
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
        assertEquals(ApiError(401, "Bad API key"), badKey shouldBeFailure badApiKey)

        app.outcome(placeOrder, In3(9_999L, "let-me-in", CreateOrder("anvil"))) shouldBeFailure noSuchUser
        assertEquals(404, noSuchUser.status)
    }

    @Test
    fun `the success side of a fallible endpoint is unchanged`() {
        val placed = app.outcome(placeOrder, In3(1L, "let-me-in", CreateOrder("rope"))).shouldBeOk()
        assertEquals("rope", placed.item)
        assertEquals(201, app.response(placeOrder, In3(1L, "let-me-in", CreateOrder("rope"))).status)
    }

    @Test
    fun `a stream can fail before its first element`() {
        val res = app.response(streamOrders, In4(9_999L, 5, null, null))
        assertEquals(404, res.status)
        assertEquals("application/json", res.contentType)
        assertTrue(res.body.contains("No user 9999"), res.body)
    }

    @Test
    fun `an empty output is 204 with no body`() {
        val res = app.response(cancelOrder, In3(1L, 5L, "let-me-in"))
        assertEquals(204, res.status)
        assertEquals("", res.body)
    }

    @Test
    fun `a raw body is echoed back`() {
        val payload = "x".repeat(50_000)
        val res = app.response(echo, rawText(payload))
        assertEquals(200, res.status)
        assertEquals(payload, res.body)
    }

    // -------------------------------------------------- forms and uploads

    @Test
    fun `a form body is posted as a form and read back as the declared types`() {
        val order = app.call(placeOrderForm, In2(1L, CreateOrder("anvil", quantity = 2)))

        assertEquals("anvil", order.item)
        assertEquals(2, order.quantity)
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

        assertEquals(ImportResult("March", "orders.csv", 2, "s-42"), result)
    }

    @Test
    fun `an optional cookie the caller omits is null rather than empty`() {
        val res = app.transport.send(
            app.request(
                importOrders,
                In4(1L, "s-42", "March", UploadedFile("a.csv", "text/csv", ByteArrayInputStream("x\n".toByteArray()))),
            ).withoutHeader("Cookie"),
        )

        assertTrue("\"session\":null" in res.body, res.body)
    }

    // ------------------------------------------------------------ lens style

    @Test
    fun `a lens-style endpoint is called with the params bag`() {
        val orders = app.collect(
            searchOrders,
            Params(mapOf(limit to 4, statusFilter to null, traceId to "abc"), null),
        )
        assertEquals(4, orders.size)
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
        assertEquals(200, res.status().intValue())

        val start = System.nanoTime()
        var firstAt = -1L
        var chunks = 0
        res.entity().dataBytes
            .runForeach({ if (firstAt < 0) firstAt = System.nanoTime(); chunks++ }, materializer)
            .toCompletableFuture()
            .join()

        val totalMs = (System.nanoTime() - start) / 1_000_000
        val firstMs = (firstAt - start) / 1_000_000

        assertEquals(8, chunks)
        assertTrue(totalMs >= 600, "stream finished suspiciously fast: ${totalMs}ms")
        assertTrue(
            firstMs < totalMs / 2,
            "first element at ${firstMs}ms of ${totalMs}ms — looks buffered, not streamed",
        )
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
            assertEquals(500, it.response(fetchUser, 1L).status)
        }
    }

    @Test
    fun `the 500 body says nothing about what went wrong inside`() {
        apiThatReturnsAStrayFailure().inMemory().use {
            val body = it.response(fetchUser, 1L).body
            // The internal message — the one naming the endpoint and the stray
            // declaration — is what a caller must not be handed.
            assertFalse(body.contains("never declared"), body)
            assertFalse(body.contains("410"), body)
            assertTrue(body.contains("Internal server error"), body)
            assertTrue(body.contains("Reference: "), body)
        }
    }

    @Test
    fun `the reference in the body is what the server logs against`() {
        val logged = mutableListOf<Triple<String, String?, String>>()
        val api = apiThatReturnsAStrayFailure(
            onServerError = { ref, ep, t -> logged += Triple(ref, ep?.toString(), t.message ?: "") },
        )

        api.inMemory().use {
            val body = it.response(fetchUser, 1L).body
            val reference = Regex("""Reference: ([0-9a-f]+)""").find(body)?.groupValues?.get(1)
            assertNotNull(reference, "no reference in $body")

            val (loggedRef, endpoint, message) = logged.single()
            assertEquals(reference, loggedRef)
            // What the caller was denied is exactly what the log gets.
            assertEquals("GET /users/{userId}", endpoint)
            assertTrue(message.contains("never declared"), message)
        }
    }

    @Test
    fun `a local run can ask for the detail back`() {
        apiThatReturnsAStrayFailure(exposeInternalErrors = true).inMemory().use {
            val body = it.response(fetchUser, 1L).body
            assertTrue(body.contains("never declared"), body)
        }
    }
}
