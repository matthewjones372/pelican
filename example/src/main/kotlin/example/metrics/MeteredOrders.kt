package example.metrics

/*
 * Meters that nobody wrote down twice.
 *
 * There is one line in this file about metrics — `metrics(registry)` in the
 * filter list — and no handler below mentions a meter, a tag or a status. What
 * a dashboard ends up split by is what the descriptions already say: the
 * method, the path *template*, the operation id, whether the endpoint is
 * deprecated, and the status the interpreter answered with.
 *
 * The endpoints are chosen to make that visible. `fetchOrder` declares a 404
 * as well as a 200, so a request that misses is a declared failure rather than
 * an exception; `placeOrder` answers 201; `listOrdersV1` is deprecated and
 * still served. Ask for any of them and then read `/admin/meters`, which is an
 * ordinary described endpoint and is therefore metered along with the rest.
 *
 * Run it with `./gradlew :example:runMetrics`.
 */

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.metrics.metrics
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.handledNow
import io.github.matthewjones372.pelican.pekko.handledOrFail
import io.github.matthewjones372.pelican.pekko.start
import io.github.matthewjones372.pelican.text
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

data class Order(val id: Long, val item: String)

data class NewOrder(val item: String)

val orderId = pathParam<Long>("orderId", description = "Which order")

val newOrder = jsonBody<NewOrder>(description = "What to order")

/**
 * Declared rather than thrown, so the 404 carries a described payload — and so
 * the meter's `status` tag comes from this declaration rather than from a guess
 * about what the handler's return value meant.
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
 * Still served, and announced as going away. `deprecated` is a tag on both
 * meters, so "is anybody still calling it?" is a query rather than a survey.
 */
val listOrdersV1 = endpoint {
    get("v1" / "orders")
    summary = "List orders, in the shape the first version of this API used"
    operationId = "listOrdersV1"
    deprecated = true
    json<List<Order>>()
}

/**
 * What the registry holds, as text. A real service scrapes this from a
 * Prometheus registry on a port of its own; here it is an ordinary described
 * endpoint, which has the pleasant side effect of metering itself.
 */
val readMeters = endpoint {
    get("admin" / "meters")
    summary = "Everything recorded so far"
    operationId = "readMeters"
    text()
}

private val stock = listOf("kettle", "teapot").mapIndexed { index, item -> Order(index + 1L, item) }

/**
 * The service, taking the registry to meter into.
 *
 * A parameter rather than a global, because a test wants a registry of its own
 * and a real service already has one — Spring's, Dropwizard's, whichever
 * `MeterRegistry` its monitoring backend supplies. Nothing here cares which:
 * `metrics(registry)` writes into it, and how it is exported is somebody
 * else's decision.
 */
fun meteredOrders(registry: MeterRegistry): Api = api(
    endpoints = listOf(
        fetchOrder handledOrFail { id ->
            val found = stock.firstOrNull { it.id == id }
            if (found == null) noSuchOrder(ApiError(404, "No order $id")) else ok(found)
        },

        placeOrder handledNow { requested -> Order(stock.size + 1L, requested.item) },

        listOrdersV1 handledNow { stock },

        readMeters handledNow { meterTable(registry) },
    ),
    codecs = JacksonCodecs,
) {
    title = "Orders"
    version = "1.0.0"

    // The whole of the instrumentation. Outermost, so that it also counts the
    // requests a filter listed after it refuses.
    filter(metrics(registry))
}

fun main() {
    val registry = SimpleMeterRegistry()
    val server = meteredOrders(registry).start(port = 8080, systemName = "metered-orders")
    println("Listening on ${server.baseUrl}")
    println("  curl ${server.baseUrl}/orders/1     # a 200")
    println("  curl ${server.baseUrl}/orders/99    # the declared 404")
    println("  curl ${server.baseUrl}/v1/orders    # the deprecated one")
    println("  curl ${server.baseUrl}/admin/meters # what was recorded")
}
