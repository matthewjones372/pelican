package example

import com.fasterxml.jackson.annotation.JsonInclude
import example.generated.OrderStatus
import example.generated.OrdersClient
import example.generated.Outcome
import io.github.matthewjones372.pelican.UploadedFile
import io.github.matthewjones372.pelican.client.ktor.KtorHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jackson.defaultMapper
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.start
import io.kotest.assertions.withClue
import io.kotest.inspectors.forAll
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.time.Duration

/**
 * The generated client over `pelican-client-ktor`, against the orders service
 * on a socket.
 *
 * `GeneratedKotlinClientTest` is the exhaustive suite and runs over the JDK
 * adapter. What is asserted here is only what a change of transport can break:
 * a streamed upload, a streamed response, and a failure read off the wire.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KtorTransportClientTest {

    private val codecs = JacksonCodecs(
        defaultMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL),
    )

    /**
     * A client of this suite's own, handed over rather than left to the one the
     * module keeps: that is the shape a service already running Ktor uses, and
     * the shape that has to close what it opened.
     */
    private val http: HttpClient = HttpClient(CIO) { install(HttpTimeout) }

    private lateinit var server: PelicanServer
    private lateinit var client: OrdersClient

    @BeforeAll
    fun setUp() {
        server = ordersApi().start(port = 0, systemName = "orders-ktor-transport")
        client = OrdersClient(server.baseUrl, codecs, KtorHttpTransport(http))
    }

    @AfterAll
    fun tearDown() {
        http.close()
        server.stop()
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
    fun `a multipart upload is streamed through the request, not held`() {
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
     * The claim the stream bridge exists for. Ktor is channel-shaped and the
     * SPI is `InputStream`-shaped, and a bridge that collected the channel
     * before handing it over would pass every other test in this file.
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

    /**
     * The transport where this had teeth. Ktor's `requestTimeoutMillis` bounds
     * the whole exchange, body included, so a client's deadline used to end a
     * stream that outlived it — while the same client on the JDK and Pekko
     * adapters, which bound the response head, streamed on.
     */
    @Test
    fun `a streamed call outlives the deadline the client was built with`() {
        val impatient = OrdersClient(
            server.baseUrl,
            codecs,
            KtorHttpTransport(http),
            timeout = Duration.ofMillis(300),
        )

        // Ten events, 100ms apart: a second of stream under a 300ms deadline.
        val ticks = impatient.watchOrders(1L, limit = 10).use { it.toList() }

        ticks.map { it.seq } shouldBe (1..10).toList()
    }
}
