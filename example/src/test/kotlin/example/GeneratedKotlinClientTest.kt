package example

import com.fasterxml.jackson.annotation.JsonInclude
import dev.pelican.UploadedFile
import dev.pelican.codegen.kotlinClient
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.jackson.defaultMapper
import dev.pelican.pekko.PelicanServer
import dev.pelican.pekko.start
import example.generated.CreateOrder
import example.generated.GetUserFailure
import example.generated.OrderStatus
import example.generated.OrdersClient
import example.generated.Outcome
import example.generated.PlaceOrderFailure
import example.generated.StreamOrdersFailure
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The generated Kotlin client, compiled and run.
 *
 * [example.generated.OrdersClient] is checked in, generated from the very
 * endpoint values this module serves. That it compiles is the build's business;
 * that it *works* is this file's. The server is real, on a real port, so a
 * generator that emits the wrong path, the wrong parameter name or the wrong
 * frame format fails here — which is the only place it can, since a generated
 * client is exactly the artefact nothing else type-checks against the service.
 *
 * Note what the calls below never mention: no URL, no query string, no JSON.
 * The types are the generated ones, so a rename in `Endpoints.kt` breaks this
 * file at compile time the moment the client is regenerated.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneratedKotlinClientTest {

    /**
     * The one thing a generated client's caller has to get right: a property
     * the schema marks optional is nullable here, because Kotlin has no other
     * way to say "may be left out" — so the codec has to leave nulls out rather
     * than write them. Jackson's default writes them, hence this mapper.
     */
    private val codecs = JacksonCodecs(
        defaultMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL),
    )

    private lateinit var server: PelicanServer
    private lateinit var client: OrdersClient

    @BeforeAll
    fun setUp() {
        server = ordersApi().start(port = 0, systemName = "orders-generated-client")
        client = OrdersClient(server.baseUrl, codecs)
    }

    @AfterAll
    fun tearDown() {
        server.stop().toCompletableFuture().join()
    }

    // ------------------------------------------------------------ plain calls

    @Test
    fun `a single json payload comes back as the declared type`() {
        val result = client.getUser(1L)
        assertEquals(Outcome.Ok(example.generated.User(1L, "Ada Lovelace", "ada@example.com")), result)
    }

    @Test
    fun `a declared failure comes back as the failure, typed`() {
        when (val result = client.getUser(999L)) {
            is Outcome.Ok -> error("expected a failure, got ${result.value}")
            is Outcome.Err -> when (val failure = result.failure) {
                // Exhaustive: adding a failure to the endpoint and regenerating
                // breaks this `when` rather than slipping through as a 500.
                is GetUserFailure.NotFound -> {
                    assertEquals(404, failure.status)
                    assertEquals("No user 999", failure.body.error)
                }
            }
        }
    }

    @Test
    fun `a 204 hands back nothing at all`() {
        client.cancelOrder(1L, 7L, xApiKey = "let-me-in")
    }

    @Test
    fun `an optional body property left out is defaulted by the server, not sent as null`() {
        val placed = client.placeOrder(1L, CreateOrder("anvil"), xApiKey = "let-me-in")
        val order = (placed as Outcome.Ok).value
        assertEquals("anvil", order.item)
        assertEquals(1, order.quantity, "the server's own default should have applied")
    }

    @Test
    fun `two failures sharing a payload type keep their own statuses`() {
        val denied = client.placeOrder(1L, CreateOrder("anvil", 2), xApiKey = "wrong")
        when (val failure = (denied as Outcome.Err).failure) {
            is PlaceOrderFailure.Unauthorized -> assertEquals("Bad API key", failure.body.error)
            is PlaceOrderFailure.NotFound -> error("wrong failure: $failure")
        }

        val missing = client.placeOrder(999L, CreateOrder("anvil"), xApiKey = "let-me-in")
        assertTrue((missing as Outcome.Err).failure is PlaceOrderFailure.NotFound, "${missing.failure}")
    }

    @Test
    fun `a form body is sent as a form, and the server reads the declared types back`() {
        // The caller writes a CreateOrder; what leaves is `item=anvil&quantity=2`,
        // shaped by the same schema the server decodes it against.
        val order = client.placeOrderForm(1L, CreateOrder("anvil", quantity = 2))

        assertEquals("anvil", order.item)
        assertEquals(2, order.quantity)
    }

    @Test
    fun `a multipart upload is streamed, and its cookie travels with it`() {
        // `UploadedFile` is the same value the *handler* is given on the other
        // side of the wire, which is why nothing has to be described twice.
        val csv = "1,anvil\n2,rope\n3,lamp\n"
        val result = client.importOrders(
            userId = 1L,
            label = "March",
            file = UploadedFile("orders.csv", "text/csv", ByteArrayInputStream(csv.toByteArray())),
            session = "s-42",
        )

        assertEquals("March", result.label)
        assertEquals("orders.csv", result.filename)
        assertEquals(3, result.lines)
        assertEquals("s-42", result.session)
    }

    @Test
    fun `an omitted cookie is left off rather than sent empty`() {
        val result = client.importOrders(
            userId = 1L,
            label = "March",
            file = UploadedFile("orders.csv", "text/csv", ByteArrayInputStream("1,anvil\n".toByteArray())),
        )

        assertNull(result.session)
    }

    @Test
    fun `a raw body is echoed back as an opaque stream`() {
        val echoed = client.echo(ByteArrayInputStream("hello pelican".toByteArray()))
        assertEquals("hello pelican", echoed.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    // ------------------------------------------------------------ streaming

    @Test
    fun `ndjson arrives as a sequence of the element type`() {
        val stream = client.streamOrders(1L, limit = 3, status = OrderStatus.SHIPPED)
        val orders = (stream as Outcome.Ok).value.toList()
        assertEquals(3, orders.size)
        assertTrue(orders.all { it.status == OrderStatus.SHIPPED }, "$orders")
    }

    @Test
    fun `a stream that fails before its first element is the declared failure`() {
        val stream = client.streamOrders(999L, limit = 3)
        assertTrue((stream as Outcome.Err).failure is StreamOrdersFailure.NotFound, "${stream.failure}")
    }

    @Test
    fun `a chunked json array is split element by element`() {
        val ids = client.listOrders(1L, limit = 4).map { it.id }.toList()
        assertEquals(listOf(1L, 2L, 3L, 4L), ids)
    }

    @Test
    fun `the lens-style endpoint reads its inputs the same way`() {
        val orders = client.searchOrders(limit = 2, xTraceId = "trace-1").toList()
        assertEquals(2, orders.size)
    }

    @Test
    fun `elements are handed over as they arrive, not after the last one`() {
        // The example emits ten events 100ms apart. Taking two must not wait
        // for the tenth, or the client is buffering what the server streamed.
        val started = System.nanoTime()
        val first = client.watchOrders(1L, limit = 10).use { ticks -> ticks.take(2).toList() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(listOf(1, 2), first.map { it.seq })
        assertTrue(elapsedMs < 700, "two of ten events took ${elapsedMs}ms — that looks buffered")
    }

    // ------------------------------------------------------------ the file itself

    @Test
    fun `the checked-in client is what the descriptions generate today`() {
        val committed = File("src/test/kotlin/example/generated/OrdersClient.kt")
        assertEquals(
            committed.readText(),
            ordersSpec().kotlinClient(packageName = "example.generated"),
            "${committed.path} is stale — run ./gradlew :example:generateKotlinClient and copy it over",
        )
    }

    @Test
    fun `a hidden endpoint is left out, as it is left out of the document`() {
        val generated = ordersSpec().kotlinClient(packageName = "example.generated")
        assertTrue("reindex" !in generated, "a hidden endpoint reached the generated client")
        assertTrue("reindex" in ordersSpec().kotlinClient("x", includeHidden = true))
        assertNotNull(reindex)
    }
}
