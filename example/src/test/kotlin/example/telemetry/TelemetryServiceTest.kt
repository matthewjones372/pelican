package example.telemetry

import example.tracing.RecordedSpans
import example.tracing.recordingTelemetry
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.github.matthewjones372.pelican.test.shouldBeFailure
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * That the report says what the requests did, and that both instruments agree
 * about which of them went wrong.
 *
 * The endpoints exist to make a report worth reading, so what is asserted here
 * is the distinction the report is drawing rather than any particular number: a
 * declared 404 is not an error, a throw is, and the slow one is slower.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TelemetryServiceTest {

    private val registry = percentileRegistry()
    private val recorded = RecordedSpans()
    private val telemetry = recordingTelemetry(recorded)

    private lateinit var app: ApiClient

    @BeforeAll
    fun setUp() {
        app = telemetryService(registry, telemetry) { report(registry, recorded) }
            .inMemory("telemetry-orders")
    }

    @AfterAll
    fun tearDown() = app.close()

    @Test
    fun `each endpoint answers what its description promises`() {
        app.call(fetchOrder, 1L).item shouldBe "kettle"
        app.outcome(fetchOrder, 99L).shouldBeFailure(noSuchOrder).status shouldBe 404
        app.call(searchOrders, In2("kettle", 5)).single().item shouldBe "kettle"
        app.call(placeOrder, NewOrder("grinder")).item shouldBe "grinder"
        app.response(fetchReceipt, 1L) shouldHaveStatus 500
        app.call(listOrdersV1, Unit).size shouldBe 3
    }

    @Test
    fun `the gate's refusal is one the instruments saw, because they wrap it`() {
        app.response(placeOrder, NewOrder("contraband")) shouldHaveStatus 403

        withClue(report(registry, recorded)) {
            report(registry, recorded) shouldContain "placeOrder"
        }
    }

    @Test
    fun `a declared failure is not an error, and a throw is`() {
        app.outcome(fetchOrder, 98L)
        app.response(fetchReceipt, 2L)

        val lines = report(registry, recorded).lines()
        val fetchOrderRow = lines.single { it.startsWith("fetchOrder") }
        val receiptRow = lines.single { it.startsWith("fetchReceipt") }

        withClue(report(registry, recorded)) {
            // A 404 the description promised is an answer, not a fault.
            fetchOrderRow.split(Regex("\\s+"))[2] shouldBe "0"
            // A throw nobody described is.
            receiptRow.split(Regex("\\s+"))[2].toInt() shouldBe 1
        }
    }

    @Test
    fun `the report names every operation that was called, from the descriptions alone`() {
        // Called here rather than relied on from another test: the registry is
        // shared across this class, and a test that only passes in one order is
        // a test that will fail in another.
        app.call(fetchOrder, 1L)
        app.call(searchOrders, In2(null, 5))
        app.call(placeOrder, NewOrder("grinder"))

        val printed = report(registry, recorded)
        withClue(printed) {
            listOf("fetchOrder", "searchOrders", "placeOrder").forEach { printed shouldContain it }
        }
    }

    @Test
    fun `the trace under the table shows the span the handler opened inside the request`() {
        app.call(placeOrder, NewOrder("cafetiere"))

        withClue(report(registry, recorded)) {
            // The handover: the row says `placeOrder`, the trace says which part
            // of it was the write.
            report(registry, recorded) shouldContain "orders.insert"
        }
    }
}
