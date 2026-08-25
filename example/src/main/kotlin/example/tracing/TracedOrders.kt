package example.tracing

/*
 * Traces that nobody wrote down twice.
 *
 * There is one line in this file about OpenTelemetry — `openTelemetry(sdk,
 * incomingHeaders = pekkoHeaders)` in the filter list — and no handler below
 * mentions a span, an attribute or a status. What a trace backend ends up
 * grouping by is what the descriptions already say: the method, the path
 * *template*, the operation id, whether the endpoint is deprecated, and the
 * status the interpreter answered with. The same line also records the
 * `http.server.request.duration` histogram the semantic conventions specify.
 *
 * The endpoints are chosen to make that visible. `fetchOrder` declares a 404 as
 * well as a 200, so a request that misses is a declared failure rather than an
 * exception, and its span is deliberately *not* marked an error; `placeOrder`
 * answers 201; `listOrdersV1` is deprecated and still served; and
 * `fetchReceipt` throws something nobody described, which is the one case that
 * does mark the span an error — and puts the message on it, in the only place
 * that message is both useful and safe, since the 500 the caller receives
 * carries a reference and nothing more.
 *
 * `pekkoHeaders` is the other half of the story: continuing a caller's trace
 * needs a header nobody declared, and reading one of those is the single thing
 * a backend-agnostic filter cannot do. Six lines, written once per service.
 *
 * Run it with `./gradlew :example:runTracing`.
 */

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.metrics.otel.openTelemetry
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.handledNow
import io.github.matthewjones372.pelican.pekko.handledOrFail
import io.github.matthewjones372.pelican.pekko.request
import io.github.matthewjones372.pelican.pekko.start
import io.github.matthewjones372.pelican.text
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.MetricReader
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

data class Order(val id: Long, val item: String)

data class NewOrder(val item: String)

val orderId = pathParam<Long>("orderId", description = "Which order")

val newOrder = jsonBody<NewOrder>(description = "What to order")

/**
 * Declared rather than thrown, so the 404 carries a described payload — and so
 * the span's `http.response.status_code` comes from this declaration rather
 * than from a guess about what the handler's return value meant.
 */
val noSuchOrder = errorJson<ApiError>(404, "No order by that id")

val fetchOrder = endpoint(orderId) {
    get("orders" / orderId)
    summary = "Fetch one order"
    operationId = "fetchOrder"
    json<Order>() orFail noSuchOrder
}

val placeOrder = endpoint(newOrder) {
    post("orders")
    summary = "Place an order"
    operationId = "placeOrder"
    json<Order>(201)
}

/**
 * Still served, and announced as going away. `pelican.deprecated` is an
 * attribute on the span and on the histogram, so "is anybody still calling it?"
 * is a query rather than a survey of downstream teams. It costs no extra series
 * either: it is a function of the method and the route, which are attributes
 * already.
 */
val listOrdersV1 = endpoint {
    get("v1" / "orders")
    summary = "List orders, in the shape the first version of this API used"
    operationId = "listOrdersV1"
    deprecated = true
    json<List<Order>>()
}

/** The one endpoint here that fails in a way nobody wrote down. See the file header. */
val fetchReceipt = endpoint(orderId) {
    get("orders" / orderId / "receipt")
    summary = "Fetch an order's receipt"
    operationId = "fetchReceipt"
    text()
}

/**
 * What was recorded, as text. A real service exports over OTLP and reads its
 * traces somewhere else entirely; here it is an ordinary described endpoint,
 * which has the pleasant side effect of tracing itself.
 */
val readSpans = endpoint {
    get("admin" / "traces")
    summary = "Every span recorded so far"
    operationId = "readSpans"
    text()
}

/**
 * How to read a request header off Pekko's own request.
 *
 * This exists because `Params` carries the inputs the endpoint *declared*, and
 * `traceparent` is not one of them — an incoming trace context is not part of
 * an API's contract and should not appear in its document. So the only way to
 * it is `Params.underlying`, which is the backend's own request object, and
 * naming that type is the one thing `pelican-metrics-otel` cannot do without
 * knowing which server is underneath. It is six lines here, and it would be six
 * different lines on http4k or Ktor.
 */
val pekkoHeaders: TextMapGetter<Params> = object : TextMapGetter<Params> {

    override fun keys(carrier: Params): Iterable<String> =
        carrier.request.headers.map { it.lowercaseName() }

    override fun get(carrier: Params?, key: String): String? =
        carrier?.request?.getHeader(key)?.map { it.value() }?.orElse(null)
}

private val stock = listOf("kettle", "teapot").mapIndexed { index, item -> Order(index + 1L, item) }

/**
 * The service, taking the OpenTelemetry instance to record into.
 *
 * A parameter rather than a global, because a test wants an SDK of its own and
 * a real service already has one — configured from the environment, or handed
 * over by whatever wired its exporter. Nothing here cares which:
 * `openTelemetry(...)` records into it, and where that goes is somebody else's
 * decision. `OpenTelemetry.noop()` is a perfectly good answer for a service
 * that has not decided yet.
 */
fun tracedOrders(telemetry: OpenTelemetry, recorded: RecordedSpans): Api = Api(
    endpoints = listOf(
        fetchOrder handledOrFail { id ->
            val found = stock.firstOrNull { it.id == id }
            if (found == null) noSuchOrder(ApiError(404, "No order $id")) else ok(found)
        },

        placeOrder handledNow { requested -> Order(stock.size + 1L, requested.item) },

        listOrdersV1 handledNow { stock },

        fetchReceipt handledNow { id -> error("the receipt printer is on fire, order $id") },

        readSpans handledNow { spanTable(recorded) },
    ),
    codecs = JacksonCodecs,
    title = "Orders",
    version = "1.0.0",

    // The whole of the instrumentation. Outermost, so that it also spans the
    // requests a filter listed after it refuses.
    filters = listOf(openTelemetry(telemetry, incomingHeaders = pekkoHeaders)),
)

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

fun main() {
    val recorded = RecordedSpans()
    val server = tracedOrders(recordingTelemetry(recorded), recorded).start(port = 8080, systemName = "traced-orders")
    println("Listening on ${server.baseUrl}")
    println("  curl ${server.baseUrl}/orders/1           # a 200")
    println("  curl ${server.baseUrl}/orders/99          # the declared 404, and not an error")
    println("  curl ${server.baseUrl}/orders/1/receipt   # a 500, and an error")
    println("  curl ${server.baseUrl}/v1/orders          # the deprecated one")
    println(
        "  curl -H 'traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01' " +
            "${server.baseUrl}/orders/1   # continues that trace",
    )
    println("  curl ${server.baseUrl}/admin/traces       # what was recorded")
}
