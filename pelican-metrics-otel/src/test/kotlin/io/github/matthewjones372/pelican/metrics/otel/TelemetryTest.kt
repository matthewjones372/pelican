package io.github.matthewjones372.pelican.metrics.otel

import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.RefusalReason
import io.github.matthewjones372.pelican.before
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.notFound
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.unauthorized
import io.github.matthewjones372.pelican.wrap
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.trace.SpanId
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * The spans and the histogram, asked of a filter chain rather than of a server.
 *
 * Nothing here starts an HTTP listener, because nothing here needs one: the
 * chain is `List<Filter>.wrap`, the same fold every interpreter builds, and
 * every attribute under test comes from the description. That the interpreters
 * then answer with the statuses recorded below is the example's
 * `TracedOrdersTest`, which asks the same questions over a socket.
 */
class TelemetryTest {

    private data class Order(val id: Long)

    private val orderId = pathParam<Long>("orderId", description = "Which order")
    private val gone = errorJson<ApiError>(404, "No order by that id")

    private val fetch = endpoint(orderId) {
        get("orders" / orderId)
        operationId = "fetchOrder"
        json<Order>() orFail gone
    }

    private val retired = endpoint {
        get("orders" / "legacy")
        operationId = "listOrdersLegacy"
        deprecated = true
        json<List<Order>>()
    }

    private val spans = InMemorySpanExporter.create()
    private val measurements = InMemoryMetricReader.create()

    private val sdk: OpenTelemetrySdk = OpenTelemetrySdk.builder()
        .setTracerProvider(
            SdkTracerProvider.builder()
                // Simple rather than batched: a test that has to wait for a
                // flush is a test that will be flaky on a loaded machine.
                .addSpanProcessor(SimpleSpanProcessor.create(spans))
                .build(),
        )
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(measurements).build())
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()

    /**
     * The headers, read off `Params.underlying`.
     *
     * A real one reads the backend's own request — `params.request.getHeader`
     * on Pekko, and the example writes that out. Here the carrier is a map put
     * where the backend's request object would be, which is enough to exercise
     * exactly the same path through the propagator.
     */
    private object FromUnderlying : TextMapGetter<Params> {

        @Suppress("UNCHECKED_CAST")
        private fun headersOf(carrier: Params?): Map<String, String> =
            (carrier?.underlying as? Map<String, String>).orEmpty()

        override fun keys(carrier: Params): Iterable<String> = headersOf(carrier).keys

        override fun get(carrier: Params?, key: String): String? = headersOf(carrier)[key]
    }

    /** One request through a chain that starts with the tracing filter, ending however [answer] says. */
    private fun request(
        endpoint: Endpoint<*, *>,
        headers: Map<String, String>? = null,
        outermost: Filter = openTelemetry(sdk, incomingHeaders = FromUnderlying),
        rest: List<Filter> = emptyList(),
        answer: (Params) -> Any?,
    ): CompletableFuture<Any?> =
        (listOf(outermost) + rest)
            .wrap { params -> CompletableFuture.completedStage(answer(params)) }
            .invoke(Params(emptyMap(), headers, endpoint))
            .toCompletableFuture()

    /** The one span that was exported, or a failure naming what was there instead. */
    private fun span(): SpanData {
        val finished = spans.finishedSpanItems
        withClue("expected exactly one span, found ${finished.map { it.name }}") { finished.size shouldBe 1 }
        return finished.single()
    }

    private fun attributesOf(span: SpanData): Map<String, Any> =
        span.attributes.asMap().entries.associate { it.key.key to it.value }

    private fun histogram(): MetricData {
        val collected = measurements.collectAllMetrics()
        withClue("expected one instrument, found ${collected.map { it.name }}") { collected.size shouldBe 1 }
        return collected.single()
    }

    @Test
    fun `the span is named and attributed from the description alone`() {
        request(fetch) { ok(Order(7)) }.join()

        val recorded = span()

        // `{method} {http.route}`, which is what the conventions ask a server
        // span to be called — and neither half was passed in anywhere.
        recorded.name shouldBe "GET /orders/{orderId}"
        recorded.kind.name shouldBe "SERVER"
        attributesOf(recorded) shouldBe mapOf(
            "http.request.method" to "GET",
            "http.route" to "/orders/{orderId}",
            "http.response.status_code" to 200L,
            "pelican.operation_id" to "fetchOrder",
            "pelican.deprecated" to false,
        )
    }

    @Test
    fun `the route is the template rather than the path the caller asked for`() {
        // The point of the whole exercise. A span named after the request's own
        // path gives a trace backend one operation per order id, and every
        // per-endpoint view it offers stops working.
        request(fetch) { ok(Order(7)) }.join()
        request(fetch) { ok(Order(8)) }.join()

        spans.finishedSpanItems.map { it.name }.toSet() shouldBe setOf("GET /orders/{orderId}")
    }

    @Test
    fun `a declared failure is a 404, and a 404 is not an error`() {
        request(fetch) { gone(ApiError(404, "no such order")) }.join()

        val recorded = span()

        attributesOf(recorded)["http.response.status_code"] shouldBe 404L
        // The conventions leave span status unset for a 4xx on a server span,
        // and they are right to: the endpoint declared this answer, and an
        // error rate that counts it measures how often callers ask for things
        // that are not there.
        recorded.status.statusCode shouldBe StatusCode.UNSET
        withClue("error.type belongs on a failure, and this was not one") {
            attributesOf(recorded).containsKey("error.type") shouldBe false
        }
    }

    @Test
    fun `a throwable is spanned as the status it will be rendered as`() {
        shouldThrow<CompletionException> { request(fetch) { notFound("no such order") }.join() }

        attributesOf(span())["http.response.status_code"] shouldBe 404L
    }

    @Test
    fun `something nobody described is a 500, an error, and a recorded exception`() {
        shouldThrow<CompletionException> {
            request(fetch) { throw IllegalStateException("the database is on fire") }.join()
        }

        val recorded = span()

        attributesOf(recorded)["http.response.status_code"] shouldBe 500L
        recorded.status.statusCode shouldBe StatusCode.ERROR
        attributesOf(recorded)["error.type"] shouldBe "500"

        // The message core deliberately keeps out of the response body, in the
        // one place it is useful and safe. Unwrapped, so the top of the stack
        // trace is the failure rather than this module's own plumbing.
        val event = recorded.events.single()
        event.attributes.asMap().entries
            .single { it.key.key == "exception.type" }
            .value shouldBe "java.lang.IllegalStateException"
    }

    @Test
    fun `a refusal raised by a filter further in is spanned too`() {
        // A `before` throws where it stands rather than failing a stage, so
        // this is the request a filter built on `handle` alone would miss —
        // and a 401 nobody can find a trace for is the one somebody will go
        // looking for.
        shouldThrow<CompletionException> {
            request(fetch, rest = listOf(before { unauthorized("present a token") })) { ok(Order(7)) }.join()
        }

        attributesOf(span())["http.response.status_code"] shouldBe 401L
    }

    @Test
    fun `the refusal still reaches the interpreter unchanged`() {
        // Tracing a request must not alter it: the throwable the chain was
        // going to raise is the throwable it raises.
        val failure = shouldThrow<CompletionException> {
            request(fetch, rest = listOf(before { unauthorized("present a token") })) { ok(Order(7)) }.join()
        }

        failure.cause.shouldNotBeNull().message shouldBe "present a token"
    }

    @Test
    fun `an inbound traceparent continues the caller's trace`() {
        val headers = mapOf(
            "traceparent" to "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
        )

        request(fetch, headers = headers) { ok(Order(7)) }.join()

        val recorded = span()
        recorded.traceId shouldBe "0af7651916cd43dd8448eb211c80319c"
        recorded.parentSpanId shouldBe "b7ad6b7169203331"
    }

    @Test
    fun `a malformed traceparent starts a trace rather than failing the request`() {
        // Whatever a caller sends is the caller's mistake, and a service that
        // answered 500 because a header did not parse would be a worse one.
        request(fetch, headers = mapOf("traceparent" to "not a trace")) { ok(Order(7)) }.join()

        val recorded = span()
        recorded.parentSpanId shouldBe SpanId.getInvalid()
        attributesOf(recorded)["http.response.status_code"] shouldBe 200L
    }

    @Test
    fun `without a getter the span joins whatever context is already current`() {
        // The agent's case: something outside Pelican has already extracted the
        // caller's context onto this thread, and the right thing to do is to
        // hang a span carrying the route off it rather than start a trace.
        val caller = sdk.getTracer("test").spanBuilder("caller").startSpan()
        caller.makeCurrent().use {
            request(fetch, outermost = openTelemetry(sdk)) { ok(Order(7)) }.join()
        }
        caller.end()

        val server = spans.finishedSpanItems.single { it.name == "GET /orders/{orderId}" }
        server.parentSpanId shouldBe caller.spanContext.spanId
    }

    @Test
    fun `a handler can nest its own work under the request's span`() {
        request(fetch) { params ->
            sdk.getTracer("test").spanBuilder("charge").setParent(params[otelContext]).startSpan().end()
            ok(Order(7))
        }.join()

        val server = spans.finishedSpanItems.single { it.name == "GET /orders/{orderId}" }
        val child = spans.finishedSpanItems.single { it.name == "charge" }
        child.parentSpanId shouldBe server.spanId
    }

    @Test
    fun `the histogram is the specified instrument, in seconds and split as the span is`() {
        request(fetch) { ok(Order(7)) }.join()

        val metric = histogram()
        metric.name shouldBe "http.server.request.duration"
        metric.unit shouldBe "s"

        val point = metric.histogramData.points.single()
        point.count shouldBe 1L
        point.sum shouldBeGreaterThan 0.0
        withClue("the bucket boundaries the conventions recommend, not the SDK's defaults") {
            point.boundaries shouldBe listOf(
                0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0,
            )
        }

        point.attributes.asMap().entries.associate { it.key.key to it.value } shouldBe mapOf(
            "http.request.method" to "GET",
            "http.route" to "/orders/{orderId}",
            "http.response.status_code" to 200L,
            "pelican.operation_id" to "fetchOrder",
            "pelican.deprecated" to false,
        )
    }

    @Test
    fun `one series per endpoint and status, however many requests arrive`() {
        repeat(3) { request(fetch) { ok(Order(7)) }.join() }
        request(fetch) { gone(ApiError(404, "no such order")) }.join()

        // The attributes come from the description, so the number of series is
        // a property of the service's shape rather than of its traffic.
        val byStatus = histogram().histogramData.points.associate { point ->
            point.attributes.asMap().entries.single { it.key.key == "http.response.status_code" }.value to point.count
        }

        byStatus shouldBe mapOf(200L to 3L, 404L to 1L)
    }

    @Test
    fun `a deprecated endpoint says so, so that its remaining callers can be found`() {
        request(retired) { listOf(Order(7)) }.join()

        attributesOf(span())["pelican.deprecated"] shouldBe true
    }

    @Test
    fun `an endpoint that never named itself is attributed rather than left out`() {
        val anonymous = endpoint {
            get("health")
            json<Order>()
        }

        request(anonymous) { Order(1) }.join()

        attributesOf(span())["pelican.operation_id"] shouldBe "unnamed"
    }

    @Test
    fun `a request that matched no description is passed through untraced`() {
        val chain = listOf(openTelemetry(sdk)).wrap { CompletableFuture.completedStage("handled" as Any?) }

        chain(Params(emptyMap(), null, endpoint = null)).toCompletableFuture().join() shouldBe "handled"

        spans.finishedSpanItems.shouldBeEmpty()
    }

    @Test
    fun `a blank scope name is refused where it is configured rather than on a request`() {
        val failure = shouldThrow<IllegalArgumentException> { openTelemetry(sdk, scopeName = " ") }

        failure.message.shouldNotBeNull()
    }

    // ------------------------------------------------ the traffic no filter sees

    @Test
    fun `a refusal is counted by reason, status and the route that refused`() {
        refusalCounter(sdk).refused(RefusalReason.BODY_LIMIT, 413, "/orders/{orderId}")

        val metric = measurements.collectAllMetrics().single()
        metric.name shouldBe "http.server.refusals"
        metric.unit shouldBe "{request}"

        val point = metric.longSumData.points.single()
        point.value shouldBe 1L
        point.attributes.asMap().entries.associate { it.key.key to it.value } shouldBe mapOf(
            "pelican.refusal_reason" to "body_limit",
            "http.response.status_code" to 413L,
            "http.route" to "/orders/{orderId}",
        )
    }

    /**
     * The attribute that decides whether this instrument is safe to publish. An
     * unmatched request carries whatever path a caller chose, so recording that
     * would be one series per probe.
     */
    @Test
    fun `a refusal with no route is attributed with one constant, not with the path`() {
        val counter = refusalCounter(sdk)
        counter.refused(RefusalReason.UNMATCHED, 404, null)
        counter.refused(RefusalReason.UNMATCHED, 404, null)

        val point = measurements.collectAllMetrics().single().longSumData.points.single()
        point.value shouldBe 2L
        point.attributes.asMap().entries
            .single { it.key.key == "http.route" }
            .value shouldBe "_unmatched"
    }

    @Test
    fun `a blank scope name is refused for the counter as well`() {
        shouldThrow<IllegalArgumentException> { refusalCounter(sdk, scopeName = " ") }
            .message.shouldNotBeNull()
    }
}
