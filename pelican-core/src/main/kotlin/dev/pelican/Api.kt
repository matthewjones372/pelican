package dev.pelican

import java.util.concurrent.CompletionStage

/**
 * The error payload. Rendered by hand rather than through the configured
 * codec, so a failure in the codec cannot prevent an error from being
 * reported.
 */
data class ApiError(val status: Int, val error: String, val detail: String? = null) {
    fun toJson(): JsonObj = jsonObj {
        "status" to status
        "error" to error
        putIfNotNull("detail", detail)
    }
}

/**
 * Descriptions only — no handlers, no server. This is all the OpenAPI
 * interpreter needs, which is why documentation can be generated in a build
 * task with no HTTP library on the classpath.
 */
class ApiSpec(
    val endpoints: List<Endpoint<*, *>>,
    /** Describes payload types. Documentation needs this; a server does not. */
    val schemas: SchemaSource,
    val title: String = "API",
    val version: String = "1.0.0",
    val description: String? = null,
    val servers: List<String> = emptyList(),
    /**
     * What every endpoint requires unless it says otherwise. An endpoint that
     * declares its own `security(...)` replaces this; one that calls
     * `noSecurity()` opts out of it.
     */
    val security: List<SecurityRequirement> = emptyList(),
)

/**
 * An endpoint bound to an implementation.
 *
 * The handler is erased to `Any?` on purpose: only a backend module knows what
 * a streaming handler actually returns, so that module owns the typed binding
 * functions and produces one of these. See `pelican-pekko`'s `handledNow`.
 */
class ServerEndpoint(
    val endpoint: Endpoint<*, *>,
    val invoke: (Params) -> CompletionStage<Any?>,
)

/**
 * The handler with this API's filters wrapped around it. Interpreters call
 * this rather than [ServerEndpoint.invoke], once per endpoint at route-build
 * time, so the chain is folded once rather than per request.
 */
fun Api.handlerFor(se: ServerEndpoint): (Params) -> CompletionStage<Any?> =
    filters.wrap(se.invoke)

/**
 * Bound endpoints plus the settings shared between them.
 *
 * A plain value, like everything else here — the endpoints are a list you build
 * however you like, and the settings are named arguments:
 *
 * ```
 * val routes = listOf(
 *     getUser     handledNow  { id -> ... },
 *     streamOrders streamedNow { (id, max) -> ... },
 * )
 *
 * val api = Api(routes, JacksonCodecs, title = "Orders")
 * ```
 */
class Api(
    val endpoints: List<ServerEndpoint>,

    /**
     * How bodies are encoded, decoded and described. `JacksonCodecs`
     * (pelican-jackson) or `KotlinxCodecs` (pelican-kotlinx). It is the only
     * thing that changes when you switch JSON libraries.
     */
    val codecs: Codecs = NoCodecs,

    val title: String = "API",
    val version: String = "1.0.0",
    val description: String? = null,
    val servers: List<String> = emptyList(),

    /**
     * What every endpoint requires unless it says otherwise. Documented, not
     * enforced: this puts the requirement in the spec and the padlock in
     * Swagger UI, and leaves checking the token to the handler or to a filter
     * in front of the server.
     */
    val security: List<SecurityRequirement> = emptyList(),

    /**
     * Cross-origin access, off unless set. Unlike [security] this one *is*
     * enforced: the interpreters answer preflights and emit the
     * `Access-Control-*` headers, working out which methods and which request
     * headers to allow from the endpoint descriptions themselves.
     *
     * ```
     * Api(routes, JacksonCodecs, cors = cors("https://app.example.com"))
     * ```
     */
    val cors: Cors? = null,

    /** How long to wait for a strict (non-streaming) request body. */
    val strictBodyTimeoutMillis: Long = 10_000,

    /**
     * The largest strict (non-streaming) request body that will be read, in
     * bytes. A body over the limit is a 413 raised before any codec sees it.
     *
     * There is a default because there has to be one: an unbounded body is a
     * way to run a service out of memory with a single request, and a library
     * that leaves the limit unset ships that as the default. Raise it for an
     * endpoint that genuinely takes a large document, or take the body as a
     * `rawBody()` stream and never hold it whole.
     */
    val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,

    /**
     * Runs around every handler, outermost first: authentication, rate limits,
     * a request log. Composed once when the route is built.
     *
     * This is where a cross-cutting rule belongs. Pelican still checks no
     * token itself — it has no idea what yours means — but a check written
     * here runs for every endpoint, including the one added next week, which
     * is not true of a check copied into each handler.
     */
    val filters: List<Filter> = emptyList(),

    /**
     * Puts an unexpected throwable's own message back in the 500 body. Off,
     * because that message is written for a log and may name a table, a host
     * or a query. Worth turning on for a local run.
     */
    val exposeInternalErrors: Boolean = false,

    /**
     * Called when a handler throws something nobody described, with the
     * reference printed in the response body. Null leaves it to the backend's
     * own logger, which is usually what you want; supply one to add the fields
     * your log aggregator wants.
     */
    val onServerError: ((reference: String, endpoint: Endpoint<*, *>?, error: Throwable) -> Unit)? = null,

    /**
     * Endpoints that must appear in [endpoints]. Nothing in Kotlin's type
     * system says a list covers a set of values, so an endpoint left out of
     * the list is otherwise silently unrouted — described, documented if you
     * generate the spec from the same list, and answering 404.
     *
     * ```
     * Api(routes, JacksonCodecs, covers = allOrderEndpoints)
     * ```
     *
     * Hand it the same list the spec is built from and forgetting to bind one
     * is a startup failure instead of a mystery.
     */
    val covers: List<Endpoint<*, *>> = emptyList(),
) {
    init {
        val bound = endpoints.map { it.endpoint }

        // Two handlers on one route: the second is unreachable, because the
        // router stops at the first match. Always a mistake, and a silent one.
        val clashes = bound
            .groupBy { it.method to it.pathSpec.template }
            .filterValues { it.size > 1 }
            .keys
        require(clashes.isEmpty()) {
            "Two endpoints are bound to the same route, so the second can never be reached: " +
                clashes.joinToString { (m, path) -> "$m $path" }
        }

        val unbound = covers.filter { declared -> bound.none { it === declared } }
        require(unbound.isEmpty()) {
            "Declared but never bound to a handler, so ${if (unbound.size == 1) "it is" else "they are"} " +
                "described and unroutable: " + unbound.joinToString()
        }
    }

    /** The description half, for the OpenAPI interpreter. */
    fun spec(): ApiSpec = ApiSpec(
        endpoints = endpoints.map { it.endpoint },
        schemas = codecs,
        title = title,
        version = version,
        description = description,
        servers = servers,
        security = security,
    )
}

/** The default body ceiling: eight megabytes. See [Api.maxBodyBytes] for why there is one at all. */
private const val DEFAULT_MAX_BODY_BYTES: Long = 8L * 1024L * 1024L
