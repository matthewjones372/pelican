package example.tracing

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
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
 * Everything recorded so far, one span to a line, in a shape that is readable
 * in a terminal:
 *
 * ```
 * GET /orders/{orderId} trace=4bf92f... parent=b7ad6b... UNSET
 *     http.request.method=GET http.response.status_code=200 http.route=/orders/{orderId} ...
 * ```
 *
 * `parent` is the interesting column when a caller sent a `traceparent`: it is
 * the caller's span id rather than a fresh one, which is the whole of what
 * context propagation buys.
 */
fun spanTable(recorded: RecordedSpans): String =
    recorded.all().joinToString("\n") { span ->
        val attributes = span.attributes.asMap()
            .map { (key, value) -> "${key.key}=$value" }
            .sorted()
            .joinToString(" ")
        val parent = if (span.parentSpanId.all { it == '0' }) "none" else span.parentSpanId

        "${span.name} trace=${span.traceId} parent=$parent ${span.status.statusCode}\n    $attributes"
    }
