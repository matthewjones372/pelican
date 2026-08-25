package example

import com.fasterxml.jackson.annotation.JsonInclude
import example.generated.OrderStatus
import example.generated.OrdersClient
import example.generated.Outcome
import io.github.matthewjones372.pelican.UploadedFile
import io.github.matthewjones372.pelican.client.pekko.PekkoHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jackson.defaultMapper
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.start
import io.kotest.assertions.withClue
import io.kotest.inspectors.forAll
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream

/**
 * The generated client over `pelican-client-pekko`, against the orders service
 * on a socket.
 *
 * `GeneratedKotlinClientTest` is the exhaustive suite and runs over the JDK
 * adapter. What is asserted here is only what a change of transport can break:
 * a streamed upload, a streamed response, and a failure read off the wire.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PekkoTransportClientTest {

    private val codecs = JacksonCodecs(
        defaultMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL),
    )

    private lateinit var server: PelicanServer
    private lateinit var client: OrdersClient

    @BeforeAll
    fun setUp() {
        server = ordersApi().start(port = 0, systemName = "orders-pekko-transport")
        // The system the service is already running, handed to the transport
        // rather than a second one started beside it. This is the reason the
        // adapter takes one at all.
        client = OrdersClient(server.baseUrl, codecs, PekkoHttpTransport(server.system))
    }

    @AfterAll
    fun tearDown() {
        server.stop().toCompletableFuture().join()
    }

    @Test
    fun `a single json payload comes back as the declared type`() {
        client.getUser(1L) shouldBe Outcome.Ok(example.generated.User(1L, "Ada Lovelace", "ada@example.com"))
    }

    @Test
    fun `a declared failure still arrives as the failure, typed`() {
        val result = client.getUser(999L)

        result.shouldBeInstanceOf<Outcome.Err<*>>()
    }

    @Test
    fun `a multipart upload is streamed through the entity, not held`() {
        val csv = "1,anvil\n2,rope\n3,lamp\n"
        val result = client.importOrders(
            userId = 1L,
            label = "March",
            manifest = UploadedFile("manifest.txt", "text/plain", ByteArrayInputStream("3 orders".toByteArray())),
            file = UploadedFile("orders.csv", "text/csv", ByteArrayInputStream(csv.toByteArray())),
            session = "s-42",
        )

        result.label shouldBe "March"
        result.filename shouldBe "orders.csv"
        result.lines shouldBe 3
        result.session shouldBe "s-42"
    }

    @Test
    fun `ndjson arrives as a sequence of the element type`() {
        val orders = (client.streamOrders(1L, limit = 3, status = OrderStatus.SHIPPED) as Outcome.Ok).value.toList()

        orders.size shouldBe 3
        orders.forAll { it.status shouldBe OrderStatus.SHIPPED }
    }

    /**
     * The claim the stream bridge exists for. Pekko is `Source`-shaped and the
     * SPI is `InputStream`-shaped, and a bridge that collected the source before
     * handing it over would pass every other test in this file.
     */
    @Test
    fun `elements are handed over as they arrive, not after the last one`() {
        // The example emits ten events 100ms apart.
        val started = System.nanoTime()
        val first = client.watchOrders(1L, limit = 10).use { ticks -> ticks.take(2).toList() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        first.map { it.seq } shouldBe listOf(1, 2)
        withClue("two of ten events took ${elapsedMs}ms — that looks buffered") { elapsedMs shouldBeLessThan 700 }
    }
}
