package example.telemetry

import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.client.pekko.PekkoHttpTransport
import io.github.matthewjones372.pelican.metrics.otel.traced
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.CompletableFuture

/**
 * Spec 0064 over a real socket: a call sent through `traced` arrives with the
 * caller's trace in its `traceparent`, the call's own span as the parent a
 * server continues from.
 */
class TracedCallTest {

    private val spans = InMemorySpanExporter.create()
    private val sdk = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()

    private object Headers : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>) = carrier.keys
        override fun get(carrier: Map<String, String>?, key: String) = carrier?.get(key)
    }

    @Test
    fun `a call made inside a span reaches the server as that span's child, over Pekko's client`() {
        val received = CompletableFuture<Map<String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/orders/7") { exchange ->
                val headers = exchange.requestHeaders.entries.associate { (name, values) ->
                    name.lowercase() to
                        values.first()
                }
                received.complete(headers)
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/orders/7"
            val caller = sdk.getTracer("test").spanBuilder("caller").startSpan()
            val status = caller.makeCurrent().use { _ ->
                PekkoHttpTransport().traced(sdk).send(ClientRequest(Method.GET, url)).toCompletableFuture().get()
                    .also { it.body.close() }.status
            }
            caller.end()
            status shouldBe 200

            val call = spans.finishedSpanItems.single { it.kind == SpanKind.CLIENT }
            call.parentSpanId shouldBe caller.spanContext.spanId
            val extracted = sdk.propagators.textMapPropagator.extract(Context.root(), received.get(), Headers)
            val parent = Span.fromContext(extracted)
            parent.spanContext.traceId shouldBe caller.spanContext.traceId
            parent.spanContext.spanId shouldBe call.spanId
        } finally {
            server.stop(0)
        }
    }
}
