package example.http4k

import dev.pelican.Api
import dev.pelican.ApiError
import dev.pelican.ServerEndpoint
import dev.pelican.http4k.bytesNow
import dev.pelican.http4k.docs.Docs
import dev.pelican.http4k.docs.startWithDocs
import dev.pelican.http4k.handledNow
import dev.pelican.http4k.handledOrFail
import dev.pelican.http4k.handledWith
import dev.pelican.http4k.streamedNow
import dev.pelican.http4k.streamedOrFail
import dev.pelican.http4k.toStream
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.ok
import dev.pelican.unauthorized
import example.ImportResult
import example.Store
import example.Tick
import example.badApiKey
import example.cancelOrder
import example.echo
import example.getUser
import example.importOrders
import example.limit
import example.listOrders
import example.noSuchUser
import example.placeOrder
import example.placeOrderForm
import example.reindex
import example.searchOrders
import example.statusFilter
import example.streamOrders
import example.watchOrders

/**
 * The same service, on the other backend.
 *
 * Every endpoint here is the identical value from `Endpoints.kt` — the file
 * that imports `dev.pelican` and nothing else. Nothing about the descriptions
 * knows which server will interpret them, so the whole difference between
 * serving this on Pekko and serving it on http4k is this file and
 * `OrdersApi.kt`: the handlers, and the type a streaming handler returns.
 *
 * Pekko's binders take a `Source<T, NotUsed>`; http4k's take a `Sequence<T>`,
 * pulled as the response body is written. The generated OpenAPI document, the
 * generated client and the typed test client are the same on both, because
 * they read the descriptions rather than the server.
 */
val ordersRoutes: List<ServerEndpoint> = listOf(
    getUser handledOrFail { id ->
        Store.user(id)?.let { ok(it) } ?: noSuchUser(ApiError(404, "No user $id"))
    },

    streamOrders streamedOrFail { (id, max, status, _) ->
        // id: Long, max: Int, status: OrderStatus?, trace: String?
        if (Store.user(id) == null) noSuchUser(ApiError(404, "No user $id"))
        else ok(Store.orders(id, max, status).asSequence())
    },

    watchOrders streamedNow { (_, max) ->
        // The sequence is walked as the socket drains, so sleeping in it delays
        // the next event rather than the whole response — the `Source.throttle`
        // of a server that answers on the calling thread.
        (1..max).asSequence().map { seq ->
            Thread.sleep(100)
            Tick(seq, "order-event-$seq")
        }
    },

    listOrders streamedNow { (id, max) ->
        Store.orders(id, max, status = null).asSequence().onEach { Thread.sleep(50) }
    },

    placeOrder handledOrFail { (id, key, req) ->
        when {
            key != "let-me-in" -> badApiKey(ApiError(401, "Bad API key"))
            Store.user(id) == null -> noSuchUser(ApiError(404, "No user $id"))
            else -> ok(Store.create(id, req))
        }
    },

    placeOrderForm handledNow { (id, req) -> Store.create(id, req) },

    // The upload arrives as a stream. Counting lines never holds the file, and
    // neither would writing it somewhere; `bytes()` is what would.
    importOrders handledNow { (_, session, label, file) ->
        ImportResult(label, file.filename, file.stream().bufferedReader().useLines { it.count() }, session)
    },

    cancelOrder handledWith { (_, _, key) ->
        if (key != "let-me-in") unauthorized("Bad API key")
    },

    reindex handledWith { key ->
        if (key != "let-me-in") unauthorized("Bad API key")
    },

    echo bytesNow { body ->
        // The request body stream is handed over unconsumed.
        body.toStream()
    },

    searchOrders streamedNow { p ->
        Store.orders(userId = 1, limit = p[limit], status = p[statusFilter]).asSequence()
    },
)

/**
 * The same `Api` value as the Pekko wiring builds, down to the codecs: only the
 * handler list differs, and only in the type its streaming handlers return.
 */
fun ordersApi(): Api = Api(
    endpoints = ordersRoutes,
    codecs = JacksonCodecs,
    title = "Orders",
    version = "1.0.0",
    description = "A Kotlin-first http4k service, described as values.",
)

/** `./gradlew :example:runHttp4k` — the same service, on the other backend. */
fun main(args: Array<String>) {
    // `./gradlew :example:runHttp4k --args=8081` when 8080 is taken.
    val port = args.firstOrNull()?.toInt() ?: DEFAULT_PORT
    val server = ordersApi().startWithDocs(port = port, docs = Docs(docsPath = "/api-docs"))
    println(
        """
        |Orders API listening on ${server.baseUrl}, served by http4k
        |
        |  GET  ${server.baseUrl}/users/1
        |  GET  ${server.baseUrl}/users/1/orders?limit=5&status=SHIPPED     (NDJSON stream)
        |  GET  ${server.baseUrl}/users/1/orders/watch?limit=10             (SSE stream)
        |  POST ${server.baseUrl}/users/1/orders   -H 'X-Api-Key: let-me-in'
        |  POST ${server.baseUrl}/echo             (streams the body back)
        |
        |  Spec ${server.baseUrl}/openapi.json
        |  Docs ${server.baseUrl}/api-docs
        |
        |Ctrl-C to stop.
        """.trimMargin(),
    )
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    server.block()
}

/** What every example binds to unless told otherwise; `--args=8081` moves it. */
private const val DEFAULT_PORT = 8080
