package example.telemetry

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // the interpreter; brings pelican-core and Pekko HTTP
 *         implementation("io.github.matthewjones372:pelican-pekko:1.0.0-RC1")
 *         // JacksonCodecs, and the schemas the document derives
 *         implementation("io.github.matthewjones372:pelican-jackson:1.0.0-RC1")
 *         // Micrometer meters
 *         implementation("io.github.matthewjones372:pelican-metrics:1.0.0-RC1")
 *         // OpenTelemetry spans and metrics
 *         implementation("io.github.matthewjones372:pelican-metrics-otel:1.0.0-RC1")
 *         // which SDK is the service's choice, not the library's
 *         implementation("io.opentelemetry:opentelemetry-sdk:1.65.0")
 *     }
 */

/*
 * One service, instrumented twice.
 *
 * There were two files here once, one metered and one traced, each making the
 * argument well and each making it alone — so the claim a reader most wants
 * checked, that both instruments come from one set of descriptions, was the one
 * neither could show. This is that claim: two filters, one endpoint list, and a
 * report at the end answering the three questions a dashboard is built from.
 *
 * There are two lines here about telemetry, both in the filter list, and no
 * handler below mentions a meter, a span, a tag or a status. What either
 * instrument ends up split by is what the descriptions already say: the method,
 * the path *template*, the operation id, whether the endpoint is deprecated,
 * and the status the interpreter answered with.
 *
 * The endpoints are chosen so the report is worth reading. `fetchOrder` is fast
 * and declares a 404, so a miss is a declared failure rather than an exception
 * and is *not* an error in either instrument. `searchOrders` is deliberately
 * slow and variable, so its p99 sits well away from its p50 and there is a
 * reason to look at a trace. `placeOrder` opens a span of its own for the
 * "database" call inside it, which is the handover: a metric says an operation
 * is slow, a trace says which part of it was. `fetchReceipt` throws something
 * nobody described, the one case that marks a span an error. `listOrdersV1` is
 * deprecated and still served, so "is anybody still calling it" is a query.
 *
 * Run it with `./gradlew :example:runTelemetry`.
 */

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.IntCodec
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.before
import io.github.matthewjones372.pelican.between
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.forbidden
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.metrics.metrics
import io.github.matthewjones372.pelican.metrics.otel.openTelemetry
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.operationName
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.handledNow
import io.github.matthewjones372.pelican.pekko.handledOrFail
import io.github.matthewjones372.pelican.pekko.request
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.text
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.context.propagation.TextMapGetter

// ============================================================== the payloads

data class Order(val id: Long, val item: String)

data class NewOrder(val item: String)

// =============================================================== the inputs

val orderId = pathParam<Long>("orderId", description = "Which order")

val term = queryParam<String>("q", description = "What to look for").optional()

val limit = queryParam("limit", IntCodec.between(1, 100), description = "Page size").default(20)

val newOrder = jsonBody<NewOrder>(description = "What to order")

/** Declared, so a miss is a described failure and neither instrument calls it an error. */
val noSuchOrder = errorJson<ApiError>(404, "No order by that id")

// ============================================================= the endpoints

val fetchOrder = endpoint(orderId) {
    get("orders" / orderId)
    summary = "Fetch one order"
    operationId = "fetchOrder"
    json<Order>() orFail noSuchOrder
}

/** Slow, and variably so: the endpoint the report should point at. */
val searchOrders = endpoint(term, limit) {
    get("orders")
    summary = "Search, slowly"
    operationId = "searchOrders"
    json<List<Order>>()
}

val placeOrder = endpoint(newOrder) {
    post("orders")
    summary = "Place an order"
    operationId = "placeOrder"
    json<Order>(201)
}

/** Throws something nobody described: the one case that marks a span an error. */
val fetchReceipt = endpoint(orderId) {
    get("orders" / orderId / "receipt")
    summary = "Fetch a receipt, from a printer that is on fire"
    operationId = "fetchReceipt"
    text()
}

/** Still served, and announced as going away. `deprecated` is a tag on both. */
val listOrdersV1 = endpoint {
    get("v1" / "orders")
    summary = "List orders, in the shape the first version used"
    operationId = "listOrdersV1"
    deprecated = true
    json<List<Order>>()
}

val readReport = endpoint {
    get("admin" / "report")
    summary = "How often, how slow, how often wrong — and the last trace"
    operationId = "readReport"
    text()
}

// ============================================================== the service

private val stock = listOf("kettle", "teapot", "cafetiere")
    .mapIndexed { index, item -> Order(index + 1L, item) }

/**
 * A gate, so that a refusal is one of the things the report has to account for.
 * It is listed after the two instruments, which is what makes its 403s visible
 * to them — a filter only sees what is inside it.
 */
val gate = before { params: Params ->
    if (params.endpoint?.operationName == "placeOrder" && params[newOrder].item == "contraband") {
        forbidden("That is not a thing this shop sells")
    }
}

/**
 * Somewhere between four and forty milliseconds, decided by the request rather
 * than by a clock, so a run of them is spread and repeatable.
 *
 * A `Thread.sleep` stands for the database call a real search makes. Wrapping it
 * in a fake repository would be the same sleep one file further away.
 */
private fun searchDelayMillis(term: String?): Long = FLOOR_MILLIS + (term?.length ?: 0) * PER_CHARACTER_MILLIS

/** Enough spread that a p99 is visibly not a p50, and small enough to run. */
private const val FLOOR_MILLIS = 4L
private const val PER_CHARACTER_MILLIS = 6L

fun telemetryService(registry: MeterRegistry, telemetry: OpenTelemetry, report: () -> String): Api = api(
    endpoints = listOf(
        fetchOrder handledOrFail { id ->
            stock.firstOrNull { it.id == id }?.let { ok(it) } ?: noSuchOrder(ApiError(404, "No order $id"))
        },

        searchOrders handledNow { (q, max) ->
            Thread.sleep(searchDelayMillis(q))
            stock.filter { q == null || it.item.contains(q) }.take(max)
        },

        placeOrder handledNow { requested ->
            // A span of its own, inside the request's. This is the handover the
            // two instruments make: the meter says `placeOrder` is slow, and
            // this says the slow part was the write.
            val span = telemetry.getTracer("example.telemetry").spanBuilder("orders.insert").startSpan()
            try {
                span.makeCurrent().use { Thread.sleep(2L) }
                Order(stock.size + 1L, requested.item)
            } finally {
                span.end()
            }
        },

        fetchReceipt handledNow { id -> error("the receipt printer is on fire, order $id") },

        listOrdersV1 handledNow { stock },

        readReport handledNow { report() },
    ),
    codecs = JacksonCodecs,
) {
    title = "Orders"
    version = "1.0.0"

    // The whole of the instrumentation, and the gate they both have to see.
    filter(metrics(registry))
    filter(openTelemetry(telemetry, incomingHeaders = pekkoHeaders))
    filter(gate)
}

/**
 * Reading `traceparent` off the request, which is the one thing a
 * backend-agnostic filter cannot do: an incoming trace context is not an input
 * the endpoint declared, so the only way to it is `Params.underlying` — the
 * backend's own request object, whose type this module knows and
 * `pelican-metrics-otel` deliberately does not.
 */
val pekkoHeaders: TextMapGetter<Params> = object : TextMapGetter<Params> {

    override fun keys(carrier: Params): Iterable<String> =
        carrier.request.headers.map { it.lowercaseName() }

    override fun get(carrier: Params?, key: String): String? =
        carrier?.request?.getHeader(key)?.map { it.value() }?.orElse(null)
}
