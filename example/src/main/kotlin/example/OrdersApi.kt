package example

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // the interpreter; brings pelican-core and Jetty's server
 *         implementation("io.github.matthewjones372:pelican-jetty:1.0.0")
 *         // JacksonCodecs, and the schemas the document derives
 *         implementation("io.github.matthewjones372:pelican-jackson:1.0.0")
 *         // startWithDocs, /openapi.json and Swagger UI
 *         implementation("io.github.matthewjones372:pelican-jetty-docs:1.0.0")
 *     }
 */

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.bytesNow
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.jetty.handledOneOf
import io.github.matthewjones372.pelican.jetty.handledOrFail
import io.github.matthewjones372.pelican.jetty.handledWith
import io.github.matthewjones372.pelican.jetty.streamedNow
import io.github.matthewjones372.pelican.jetty.streamedOrFail
import io.github.matthewjones372.pelican.jetty.toSequence
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.lastEventId
import io.github.matthewjones372.pelican.of
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.unauthorized

/**
 * The Jetty wiring: every description from Endpoints.kt paired with its
 * implementation. This is the only file in the example that knows a stream is a
 * `Sequence`, pulled one element per write; the descriptions don't, and neither
 * does the OpenAPI generator.
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
        else ok(Store.orders(id, max, status).asSequence())
    },

    watchOrders streamedNow { (_, max) ->
        // A reconnect says where it got to, so the run starts after that
        // rather than replaying what the caller already has.
        val from = lastEventId()?.toIntOrNull() ?: 0
        (from + 1..from + max).asSequence()
            .spaced(WATCH_MILLIS)
            .map { seq -> Tick(seq, "order-event-$seq") }
    },

    listOrders streamedNow { (id, max) ->
        // Throttled so the example demonstrates what the type promises: the
        // array is framed and flushed as elements arrive, not assembled first.
        Store.orders(id, max, status = null).asSequence().spaced(LIST_MILLIS)
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

    // Both directions at once: each row is read off the upload only when the
    // download asks for the next answer, so neither side is held in memory.
    ingestOrders streamedNow { (id, rows) ->
        rows.toSequence().map { req -> Store.create(id, req) }
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
        // The request body is handed over unconsumed.
        body.toSequence()
    },

    // The lens-style endpoint reads its inputs from the bag by key — including
    // the multi-valued ones, which arrive as the `List<String>?` they were
    // declared as rather than as a string somebody has to split.
    searchOrders streamedNow { p ->
        val items = p[itemFilter].orEmpty().toSet()
        Store.orders(userId = 1, limit = p[limit], status = p[statusFilter]).asSequence()
            .filter { order -> items.isEmpty() || order.item in items }
    },
)

/** A pause before every element but the first; on a virtual thread a sleep holds no platform thread. */
private fun <T> Sequence<T>.spaced(millis: Long): Sequence<T> =
    mapIndexed { i, element -> element.also { if (i > 0) Thread.sleep(millis) } }

private const val WATCH_MILLIS = 100L
private const val LIST_MILLIS = 50L

fun ordersApi(): Api = api(
    endpoints = ordersRoutes,
    // The one argument that decides which JSON library serves this API. Swap it
    // for KotlinxCodecs and no endpoint description changes.
    codecs = JacksonCodecs,
) {
    title = "Orders"
    version = "1.0.0"
    description = "A Kotlin-first Jetty service, described as values."
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
 * else. `startWithDocs(docs = ordersDocs)` is what adds the pages: Swagger UI
 * at /api-docs to try a call, and a Redoc reference at /reference to read, in
 * the shop's own look (spec 0071).
 */
val ordersDocs = docs {
    docsPath = "/api-docs"
    reference("/reference") {
        logo(ORDERS_LOGO, altText = "Orders", backgroundColor = "#F2FBFA")
        stylesheet("https://fonts.googleapis.com/css2?family=Inter:wght@400;600&display=swap")
        options = jsonObj {
            put(
                "theme",
                jsonObj {
                    put("colors", jsonObj { put("primary", jsonObj { "main" to "#0B7A75" }) })
                    put("typography", jsonObj { "fontFamily" to "Inter, sans-serif" })
                    put("sidebar", jsonObj { "backgroundColor" to "#F2FBFA" })
                },
            )
            "expandResponses" to "200,201"
        }
    }
}

/** A box with a tick, small enough to ride in the page as a `data:` URI rather than be served from somewhere. */
private const val ORDERS_LOGO =
    "data:image/svg+xml;utf8,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E" +
        "%3Cpath fill='%230B7A75' d='M3 7l9-4 9 4v10l-9 4-9-4z'/%3E" +
        "%3Cpath fill='none' stroke='white' stroke-width='2' d='M8 12l3 3 5-6'/%3E%3C/svg%3E"
