package example

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.of
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pekko.*
import io.github.matthewjones372.pelican.pekko.docs.Docs
import io.github.matthewjones372.pelican.unauthorized
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Source
import java.time.Duration

/**
 * The Pekko wiring: every description from Endpoints.kt paired with its
 * implementation. This is the only file in the example that knows a stream is a
 * `Source` — the descriptions don't, and neither does the OpenAPI generator.
 */
val ordersRoutes: List<ServerEndpoint> = listOf(
    // Declared failures are returned, not thrown: the endpoint's type says
    // this handler answers with a User or with `noSuchUser`, and the compiler
    // holds it to that.
    getUser handledOrFail { id ->
        Store.user(id)?.let { ok(it) } ?: noSuchUser(ApiError(404, "No user $id"))
    },

    streamOrders streamedOrFail { (id, max, status, _) ->
        // id: Long, max: Int, status: OrderStatus?, trace: String?
        if (Store.user(id) == null) noSuchUser(ApiError(404, "No user $id"))
        else ok(Source.from(Store.orders(id, max, status)))
    },

    watchOrders streamedNow { (_, max) ->
        Source.range(1, max)
            .throttle(1, Duration.ofMillis(100))
            .map { seq -> Tick(seq, "order-event-$seq") }
    },

    listOrders streamedNow { (id, max) ->
        // Throttled so the example demonstrates what the type promises: the
        // array is framed and flushed as elements arrive, not assembled first.
        Source.from(Store.orders(id, max, status = null))
            .throttle(1, Duration.ofMillis(50))
    },

    placeOrder handledOrFail { (id, key, req) ->
        when {
            key != "let-me-in" -> badApiKey(ApiError(401, "Bad API key"))

            Store.user(id) == null -> noSuchUser(ApiError(404, "No user $id"))

            req.quantity > Store.BURST_LIMIT -> throttled(
                ApiError(429, "At most ${Store.BURST_LIMIT} of anything in one order"),
                retryAfter of Store.RETRY_AFTER_SECONDS,
            )

            else -> ok(Store.create(id, req))
        }
    },

    submitOrder handledOneOf { (id, key, req) ->
        when {
            key != "let-me-in" -> badApiKey(ApiError(401, "Bad API key"))

            req.quantity > Store.BURST_LIMIT ->
                orderQueued(Queued("ticket-$id-${req.item}", position = req.quantity))

            else -> {
                val order = Store.create(id, req)
                orderPlaced(order, orderAt of "/users/$id/orders/${order.id}")
            }
        }
    },

    placeOrderForm handledNow { (id, req) -> Store.create(id, req) },

    // The upload arrives as a stream. Counting lines never holds the file, and
    // neither would writing it somewhere; `bytes()` is what would.
    importOrders handledNow { (_, session, label, manifest, file) ->
        val declared = manifest.text().trim()
        ImportResult(
            label,
            file.filename,
            file.stream().bufferedReader().useLines { it.count() },
            session,
            declared,
        )
    },

    // The body arrives already decoded into whichever branch the `method`
    // property selected, so the handler matches on the Kotlin type rather than
    // on a string it has to check itself.
    payOrder handledOrFail { (id, order, key, method) ->
        when {
            key != "let-me-in" -> badApiKey(ApiError(401, "Bad API key"))
            Store.user(id) == null -> noSuchUser(ApiError(404, "No user $id"))
            else -> ok(Store.pay(order, method))
        }
    },

    cancelOrder handledWith { (_, _, key) ->
        if (key != "let-me-in") unauthorized("Bad API key")
    },

    reindex handledWith { key ->
        if (key != "let-me-in") unauthorized("Bad API key")
    },

    echo bytesNow { body ->
        // The request body source is handed over unconsumed.
        body.toSource().mapMaterializedValue { NotUsed.getInstance() }
    },

    // The lens-style endpoint reads its inputs from the bag by key — including
    // the multi-valued ones, which arrive as the `List<String>?` they were
    // declared as rather than as a string somebody has to split.
    searchOrders streamedNow { p ->
        val items = p[itemFilter].orEmpty().toSet()
        Source.from(Store.orders(userId = 1, limit = p[limit], status = p[statusFilter]))
            .filter { order -> items.isEmpty() || order.item in items }
    },
)

fun ordersApi(): Api = api(
    endpoints = ordersRoutes,
    // The one argument that decides which JSON library serves this API. Swap it
    // for KotlinxCodecs and no endpoint description changes.
    codecs = JacksonCodecs,
) {
    title = "Orders"
    version = "1.0.0"
    description = "A Kotlin-first Pekko HTTP service, described as values."
    // Declared on the served API as well as on the spec, because it is a fact
    // about the service rather than about the document: this is a call it
    // makes. Nothing here routes it — see `WebhooksTest` in pelican-core.
    webhooks = allWebhooks

    // No `servers` entry on purpose. Swagger UI's "Try it out" calls the URLs
    // listed there, and a hardcoded one pins every call to that exact origin —
    // so opening the page on 127.0.0.1 while the spec says localhost makes each
    // call cross-origin, and the browser blocks it. Left empty, Swagger UI uses
    // the origin the page was loaded from, which is right either way.
}

/**
 * Where this service publishes itself. Separate from the `Api` on purpose:
 * serving docs is opt-in, and `start()` alone serves the endpoints and nothing
 * else. `startWithDocs(docs = ordersDocs)` is what adds the two pages.
 */
val ordersDocs = docs { docsPath = "/api-docs" }
