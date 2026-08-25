package example.tracing

import io.github.matthewjones372.pelican.pekko.start
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * What the one `openTelemetry(...)` line in `TracedOrders.kt` actually
 * produced, asked over a socket.
 *
 * Each case asks the same question twice: what came back over the wire, and
 * what the instrumentation says came back. They are asserted together on
 * purpose, and it is the same reason `MetricsAcrossBackendsTest` exists for the
 * meters. A filter cannot see the response the interpreter renders — it has
 * unwound by then — so the status on the span is read off the description by
 * `Endpoint.statusFor`, and the risk that buys is drift: the description says
 * 201, the server sends 200, and a trace is wrong in a way nobody notices for a
 * quarter. Comparing the two is what makes that drift a build failure.
 *
 * A real server rather than the in-memory transport, because two of the claims
 * below are about bytes on the wire: a `traceparent` header the endpoint never
 * declared, and a 500 whose body deliberately says less than its span does.
 *
 * The server is started once for the class, so spans accumulate across the
 * tests. Nothing below assumes otherwise — each case looks up the spans it is
 * about rather than expecting a particular number of them, and the histogram is
 * compared against the spans rather than against a count written down here.
 */
class TracedOrdersTest {

    companion object {
        private val recorded = RecordedSpans()
        private val measurements = InMemoryMetricReader.create()

        private val server = tracedOrders(recordingTelemetry(recorded, measurements), recorded)
            .start(port = 0, systemName = "traced-orders-test")

        private val http: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stop() {
            server.stop().toCompletableFuture().join()
        }
    }

    private fun get(path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create(server.baseUrl + path))
        headers.forEach { (name, value) -> request.header(name, value) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun attributesOf(span: SpanData): Map<String, Any> =
        span.attributes.asMap().entries.associate { it.key.key to it.value }

    /** The spans for one route, newest last. */
    private fun spansFor(route: String): List<SpanData> =
        recorded.all().filter { attributesOf(it)["http.route"] == route }

    @Test
    fun `the span is named for the template, not for the id that was asked for`() {
        get("/orders/1").statusCode() shouldBe 200
        get("/orders/2").statusCode() shouldBe 200

        val spans = spansFor("/orders/{orderId}").filter { attributesOf(it)["http.response.status_code"] == 200L }

        withClue("two ids, one operation — which is the whole point of reading the template") {
            spans.map { it.name }.toSet() shouldBe setOf("GET /orders/{orderId}")
        }
        attributesOf(spans.last()) shouldContainExactly mapOf(
            "http.request.method" to "GET",
            "http.route" to "/orders/{orderId}",
            "http.response.status_code" to 200L,
            "pelican.operation_id" to "fetchOrder",
            "pelican.deprecated" to false,
        )
    }

    @Test
    fun `the declared 404 is a 404 on the wire and not an error on the span`() {
        val response = get("/orders/99")

        response.statusCode() shouldBe 404
        response.body() shouldContain "No order 99"

        val span = spansFor("/orders/{orderId}").last { attributesOf(it)["http.response.status_code"] == 404L }
        // The endpoint declared this answer. A trace backend that counted it as
        // a failure would be reporting how often callers ask for orders that do
        // not exist, which is not what anybody means by an error rate.
        span.status.statusCode shouldBe StatusCode.UNSET
        withClue(attributesOf(span).toString()) { attributesOf(span).containsKey("error.type") shouldBe false }
    }

    @Test
    fun `the 500 says less to the caller than it does to the trace`() {
        val response = get("/orders/1/receipt")

        response.statusCode() shouldBe 500
        // Core keeps the message out of the body on purpose: it is written for
        // whoever is debugging, and the caller is not that person.
        response.body() shouldContain "Reference:"
        response.body() shouldNotContain "receipt printer"

        val span = spansFor("/orders/{orderId}/receipt").last()
        span.status.statusCode shouldBe StatusCode.ERROR
        attributesOf(span)["error.type"] shouldBe "500"

        // And here it is, in the one place it is both useful and safe.
        val exception = span.events.single().attributes.asMap().entries.associate { it.key.key to it.value }
        exception["exception.type"] shouldBe "java.lang.IllegalStateException"
        exception["exception.message"].toString() shouldContain "receipt printer"
    }

    @Test
    fun `a caller's traceparent is continued rather than replaced`() {
        val caller = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"

        get("/orders/1", mapOf("traceparent" to caller)).statusCode() shouldBe 200

        // The header is not an input this API declares — it is nowhere in the
        // document — so this is `pekkoHeaders` in TracedOrders.kt doing its job.
        val span = spansFor("/orders/{orderId}")
            .last { it.traceId == "0af7651916cd43dd8448eb211c80319c" }
        span.parentSpanId shouldBe "b7ad6b7169203331"
    }

    @Test
    fun `a request that carries no traceparent starts its own trace`() {
        get("/v1/orders").statusCode() shouldBe 200

        val span = spansFor("/v1/orders").last()
        withClue("nothing to continue, so this is the root of a new trace") {
            span.parentSpanId.all { it == '0' } shouldBe true
        }
        // Still served, and still announced as going away.
        attributesOf(span)["pelican.deprecated"] shouldBe true
    }

    @Test
    fun `the histogram counts exactly the requests the spans describe`() {
        get("/orders/1").statusCode() shouldBe 200
        get("/orders/99").statusCode() shouldBe 404

        val fromSpans = recorded.all()
            .groupingBy { attributesOf(it).filterKeys { key -> key != "error.type" } }
            .eachCount()

        val points = measurements.collectAllMetrics()
            .single { it.name == "http.server.request.duration" }
            .histogramData
            .points

        val fromHistogram = points.associate { point ->
            point.attributes.asMap().entries
                .associate { it.key.key to it.value }
                .filterKeys { key -> key != "error.type" } to point.count.toInt()
        }

        withClue("the histogram and the spans are recorded from the same status, so they cannot disagree") {
            fromHistogram shouldBe fromSpans
        }
        points.sumOf { it.count } shouldBeGreaterThan 0L
    }

    @Test
    fun `the spans read back through an ordinary endpoint, and that one is traced too`() {
        get("/orders/1")

        val table = get("/admin/traces").body()

        table shouldContain "GET /orders/{orderId}"
        table shouldContain "http.route=/orders/{orderId}"
        table shouldContain "pelican.operation_id=fetchOrder"

        // `/admin/traces` is described like everything else, so it is traced
        // like everything else — but only from the request *after* the one that
        // rendered this table, which is why it is absent from it.
        spansFor("/admin/traces").shouldNotBeEmpty()
        table shouldNotContain "/admin/traces"
    }
}
