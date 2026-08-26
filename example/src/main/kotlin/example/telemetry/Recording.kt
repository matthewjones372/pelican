package example.telemetry

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.MetricReader
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Somewhere for finished spans to go that is not a collector.
 *
 * A service that is actually observed exports over OTLP and the spans land in
 * Jaeger, Tempo or whatever the organisation runs. This keeps them in memory so
 * that the example can show what came out of one `openTelemetry(sdk)` line
 * without a container to run first — the same trade `MeterTable.kt` makes next
 * door, and for the same reason.
 *
 * A `CopyOnWriteArrayList` because the reader is an HTTP handler and the writer
 * is whichever thread finished a request, and a list read while it is being
 * appended to is a `ConcurrentModificationException` waiting for a demo.
 */
class RecordedSpans : SpanExporter {

    private val finished = CopyOnWriteArrayList<SpanData>()

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        finished.addAll(spans)
        return CompletableResultCode.ofSuccess()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

    /** Everything exported so far, oldest first. */
    fun all(): List<SpanData> = finished.toList()
}

/**
 * An SDK that keeps its spans in memory and understands `traceparent`.
 *
 * The propagator is the part worth noticing: `OpenTelemetrySdk` defaults to a
 * no-op one, so a service that never sets this extracts nothing however good
 * its header getter is, and every trace starts at its own front door.
 *
 * [metrics] is optional because the two signals have different homes here. The
 * spans have somewhere to go — `/admin/traces` reads them back — while the
 * `http.server.request.duration` histogram wants a reader, and a runnable
 * example has nothing useful to do with one. `TracedOrdersTest` passes an
 * in-memory reader and holds the histogram to the same standard as the spans.
 */
fun recordingTelemetry(recorded: RecordedSpans, metrics: MetricReader? = null): OpenTelemetrySdk {
    val builder = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(recorded)).build())
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))

    if (metrics != null) {
        builder.setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
    }
    return builder.build()
}
