package example.telemetry

import example.tracing.RecordedSpans
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit

/**
 * The three questions a dashboard is built from, per operation: how often, how
 * slow, how often wrong.
 *
 * The previous examples printed the registry, which showed *that* the meters
 * carry the descriptions' dimensions without showing what having them is for.
 * Every column below is grouped by `operation`, and nothing in the service
 * tagged anything: the dimension is the endpoint's `operationId`, put there by
 * the filter from the description.
 *
 * A real service exports the registry to something that draws this. This is the
 * smallest thing that makes the point without a second dependency.
 */
fun report(registry: MeterRegistry, recorded: RecordedSpans): String = buildString {
    val timers = registry.meters
        .filterIsInstance<Timer>()
        .groupBy { it.id.getTag("operation") ?: "?" }
        .toSortedMap()

    appendLine("operation        calls  errors      p50      p99")
    timers.forEach { (operation, meters) ->
        val calls = meters.sumOf { it.count() }
        // A declared failure is not an error: `fetchOrder`'s 404 is an answer
        // the description promised, and counting it here would make a working
        // service look broken.
        val errors = meters.filter { (it.id.getTag("status")?.toIntOrNull() ?: 0) >= SERVER_ERROR }
            .sumOf { it.count() }
        val p50 = meters.maxOf { it.percentile(HALF) }
        val p99 = meters.maxOf { it.percentile(NEARLY_ALL) }

        appendLine(
            operation.padEnd(NAME_WIDTH) +
                calls.toString().padStart(COUNT_WIDTH) +
                errors.toString().padStart(COUNT_WIDTH) +
                millis(p50).padStart(TIME_WIDTH) +
                millis(p99).padStart(TIME_WIDTH) +
                if (p99 > SLOW_MILLIS * MILLIS_TO_NANOS) "   <- slow: see the trace below" else "",
        )
    }

    appendLine()
    appendLine("the last request, as spans:")
    appendLine(lastTrace(recorded))
}

/**
 * The most recent trace, indented by depth.
 *
 * This is the handover, and the reason the two instruments are worth running
 * together: the table above says an operation is slow, and this says which part
 * of it was.
 */
private fun lastTrace(recorded: RecordedSpans): String {
    val spans = recorded.all()
    val traceId = spans.lastOrNull()?.traceId ?: return "  (nothing recorded yet)"
    val inTrace = spans.filter { it.traceId == traceId }

    val byParent = inTrace.groupBy { it.parentSpanId }
    val roots = inTrace.filter { span -> inTrace.none { it.spanId == span.parentSpanId } }

    fun render(span: io.opentelemetry.sdk.trace.data.SpanData, depth: Int): String = buildString {
        val took = millis(span.endEpochNanos - span.startEpochNanos)
        append("  ".repeat(depth + 1))
        append(span.name.padEnd(NAME_WIDTH - depth * 2))
        append(took.padStart(TIME_WIDTH))
        if (span.status.statusCode.name == "ERROR") append("   error")
        byParent[span.spanId].orEmpty().forEach { child ->
            append("\n")
            append(render(child, depth + 1))
        }
    }

    return roots.joinToString("\n") { render(it, 0) }
}

private fun Timer.percentile(at: Double): Double =
    takeSnapshot().percentileValues()
        .firstOrNull { it.percentile() >= at }
        ?.value(TimeUnit.NANOSECONDS)
        ?: totalTime(TimeUnit.NANOSECONDS)

// `Locale.ROOT`, so a machine with a comma decimal separator prints the same
// report as one without: this is read beside numbers, not prose.
private fun millis(nanos: Double): String =
    String.format(java.util.Locale.ROOT, "%.1fms", nanos / MILLIS_TO_NANOS)

private fun millis(nanos: Long): String = millis(nanos.toDouble())

private const val SERVER_ERROR = 500
private const val HALF = 0.5
private const val NEARLY_ALL = 0.99
private const val NAME_WIDTH = 16
private const val COUNT_WIDTH = 7
private const val TIME_WIDTH = 9
private const val MILLIS_TO_NANOS = 1_000_000.0
private const val SLOW_MILLIS = 20
