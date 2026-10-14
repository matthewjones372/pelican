package io.github.matthewjones372.pelican.metrics.otel

import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.ClientTransport
import io.github.matthewjones372.pelican.Method
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/** Spec 0064: `traced` sends the caller's trace with every call, and records the call as a client span. */
class ClientTracingTest {

    private val spans = InMemorySpanExporter.create()
    private val measurements = InMemoryMetricReader.create()

    private val sdk: OpenTelemetrySdk = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(measurements).build())
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()

    private val tracer = sdk.getTracer("test")

    /** [block] inside a span of the caller's own, and that span. */
    private fun <A> inSpan(block: () -> A): Pair<A, SpanData> {
        val outer = tracer.spanBuilder("caller").startSpan()
        val answer = outer.makeCurrent().use { block() }
        outer.end()
        return answer to spans.finishedSpanItems.single { it.name == "caller" }
    }

    /** A transport that answers every call with [status] and no body. */
    private fun answering(status: Int) = ClientTransport {
        CompletableFuture.completedFuture(ClientResponse(status, emptyList(), ByteArrayInputStream(ByteArray(0))))
    }

    private fun client(): SpanData = spans.finishedSpanItems.single { it.kind == SpanKind.CLIENT }

    /** The context a server reads off [headers], as `openTelemetry`'s filter does. */
    private fun extracted(headers: Map<String, String>): Context =
        sdk.propagators.textMapPropagator.extract(
            Context.root(), headers,
            object : TextMapGetter<Map<String, String>> {
                override fun keys(carrier: Map<String, String>) = carrier.keys
                override fun get(carrier: Map<String, String>?, key: String) = carrier?.get(key)
            },
        )

    @Test
    fun `a call made inside a span carries it, as the call's own span, for the server to continue`() {
        val sent = CompletableFuture<ClientRequest>()
        val recording = ClientTransport { request ->
            sent.complete(request)
            CompletableFuture.completedFuture(ClientResponse(200, emptyList(), ByteArrayInputStream(ByteArray(0))))
        }
        val (_, caller) = inSpan {
            val call = ClientRequest(Method.GET, "http://orders.internal/orders/7")
            recording.traced(sdk).send(call).toCompletableFuture().get()
        }

        val call = client()
        call.name shouldBe "GET"
        call.parentSpanId shouldBe caller.spanId
        call.traceId shouldBe caller.traceId
        call.attributes.get(AttributeKey.stringKey("url.full")) shouldBe "http://orders.internal/orders/7"
        call.attributes.get(AttributeKey.longKey("http.response.status_code")) shouldBe 200L
        call.status.statusCode shouldBe StatusCode.UNSET

        val headers = sent.get().headers.associate { (name, value) -> name to value }
        val parent = Span.fromContext(extracted(headers)).spanContext
        parent.traceId shouldBe caller.traceId
        parent.spanId shouldBe call.spanId
    }

    @Test
    fun `an answer of 4xx or 5xx is an error on a client span, and is handed back as it came`() {
        listOf(404, 503).forEach { answered ->
            spans.reset()
            val transport = answering(answered)
            val call = ClientRequest(Method.GET, "http://orders.internal/orders")
            val response = transport.traced(sdk).send(call).toCompletableFuture().get()
            response.status shouldBe answered
            client().status.statusCode shouldBe StatusCode.ERROR
            client().attributes.get(AttributeKey.stringKey("error.type")) shouldBe answered.toString()
            client().attributes.get(AttributeKey.longKey("server.port")) shouldBe 80L
        }
    }

    @Test
    fun `a call that never got an answer ends its span as an error, and fails the caller as it would have`() {
        val refused = ClientTransport { CompletableFuture.failedFuture(IOException("connection refused")) }
        val failed = shouldThrow<ExecutionException> {
            val sent = refused.traced(sdk).send(ClientRequest(Method.POST, "https://orders.internal/orders"))
            sent.toCompletableFuture().get()
        }
        failed.cause.shouldNotBeNull().message shouldBe "connection refused"
        client().status.statusCode shouldBe StatusCode.ERROR
        client().attributes.get(AttributeKey.stringKey("error.type")) shouldBe "java.io.IOException"
        client().attributes.get(AttributeKey.longKey("server.port")) shouldBe 443L
        client().events.single().name shouldBe "exception"
    }

    @Test
    fun `every call is measured as the conventions name it`() {
        val call = ClientRequest(Method.GET, "http://orders.internal:8080/orders")
        answering(200).traced(sdk).send(call).toCompletableFuture().get()
        val metric = measurements.collectAllMetrics().single { it.name == "http.client.request.duration" }
        metric.unit shouldBe "s"
        val point = metric.histogramData.points.single()
        point.count shouldBe 1L
        point.attributes.get(AttributeKey.longKey("http.response.status_code")) shouldBe 200L
        point.attributes.get(AttributeKey.stringKey("server.address")) shouldBe "orders.internal"
    }
}
