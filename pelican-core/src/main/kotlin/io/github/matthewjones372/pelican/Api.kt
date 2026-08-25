package io.github.matthewjones372.pelican

import java.util.concurrent.CompletionStage

/** Rendered by hand, so a failing codec cannot prevent an error being reported. */
data class ApiError(val status: Int, val error: String, val detail: String? = null) {
    fun toJson(): JsonObj = jsonObj {
        "status" to status
        "error" to error
        putIfNotNull("detail", detail)
    }
}

/**
 * Descriptions only. All the OpenAPI interpreter needs, which is why docs can
 * be generated in a build task with no HTTP library on the classpath.
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
     * What every endpoint requires unless it says otherwise. Not inherited by
     * a [Webhook]: see [webhooks].
     */
    val security: List<SecurityRequirement> = emptyList(),

    /**
     * The calls this service sends — OpenAPI 3.1's `webhooks`.
     */
    val webhooks: List<Webhook> = emptyList(),
) {
    init {
        refuseWebhooksAmong(endpoints)
        refuseRepeatedWebhooks(webhooks)
        refuseRepeatedRoutes(endpoints)
        refuseRepeatedOperationNames(endpoints, webhooks)
    }
}

/**
 * The document keys an operation by path and method, so the second of two would
 * replace the first and never be published. [Api] refuses the same thing in the
 * words of a router; this is the description's own reason, and it is the one a
 * documentation-only build reaches.
 */
private fun refuseRepeatedRoutes(endpoints: List<Endpoint<*, *>>) {
    val clashes = endpoints
        .groupBy { it.method to it.pathSpec.template }
        .filterValues { it.size > 1 }
        .keys
    require(clashes.isEmpty()) {
        "Two endpoints are described for " + clashes.joinToString { (m, path) -> "$m $path" } +
            ", and a document has one entry per path and method — the second would replace the " +
            "first and never be published. Describe one, or give it a path of its own."
    }
}

/**
 * `operationId` has to be unique across a document, and it is also the method
 * name a generated client takes, so two of them produce a file that does not
 * compile. Checked on the resolved name, which covers a derived one as well as
 * a declared one.
 *
 * A webhook counts once per name rather than once per entry: several methods
 * under one name are deliberately allowed — [refuseRepeatedWebhooks] is where
 * that rule lives — and they are one key in `webhooks` between them. What is
 * refused here is a name an endpoint and a webhook both answer to.
 */
private fun refuseRepeatedOperationNames(endpoints: List<Endpoint<*, *>>, webhooks: List<Webhook>) {
    val named = endpoints.map { it.operationName to it.toString() } +
        webhooks.distinctBy { it.operationName }.map { it.operationName to it.toString() }
    val clashes = named.groupBy { it.first }.filterValues { it.size > 1 }
    require(clashes.isEmpty()) {
        clashes.entries.joinToString("; ") { (name, owners) ->
            "The operationId '$name' is declared by " + owners.joinToString { it.second }
        } + ". It is the document's key for an operation and a generated client's method name, " +
            "so the second would replace the first. Give one of them an operationId of its own."
    }
}

/**
 * A webhook's operation put where routes go. The one way it could still reach a
 * router, since [Webhook.operation] is public for the document to read — and
 * bound to a handler it would be served at `/`.
 */
private fun refuseWebhooksAmong(endpoints: List<Endpoint<*, *>>) {
    val webhooks = endpoints.mapNotNull { it.webhookName }
    require(webhooks.isEmpty()) {
        "The webhook(s) ${webhooks.joinToString()} are listed as endpoints, and a webhook is a call this " +
            "service sends rather than one it answers: it has no path, so binding it would serve it at `/`. " +
            "Pass them as `webhooks = listOf(...)` instead."
    }
}

/** Two entries for one name and method would be one key in the document, and the second would win. */
private fun refuseRepeatedWebhooks(webhooks: List<Webhook>) {
    val clashes = webhooks
        .groupBy { it.name to it.operation.method }
        .filterValues { it.size > 1 }
        .keys
    require(clashes.isEmpty()) {
        "Two webhooks are declared for " + clashes.joinToString { (name, method) -> "$name ($method)" } +
            ", and the document has one entry per name and method — the second would replace the first. " +
            "Several methods under one name are fine; two of the same are not."
    }
}

/**
 * An endpoint bound to an implementation. The handler is erased to `Any?`
 * because only a backend knows what a streaming handler returns, so each owns
 * its typed binders and produces one of these.
 */
class ServerEndpoint(
    val endpoint: Endpoint<*, *>,
    val invoke: (Params) -> CompletionStage<Any?>,
)

/**
 * The handler with this API's filters wrapped around it. Called once per
 * endpoint at route-build time, so the chain is folded once, not per request.
 */
fun Api.handlerFor(se: ServerEndpoint): (Params) -> CompletionStage<Any?> =
    filters.wrap(se.invoke)

/** Bound endpoints plus the settings shared between them. */
class Api(
    val endpoints: List<ServerEndpoint>,

    /**
     * How bodies are encoded, decoded and described — `JacksonCodecs`,
     * `KotlinxCodecs`. The only thing that changes when you switch libraries.
     */
    val codecs: Codecs = NoCodecs,

    val title: String = "API",
    val version: String = "1.0.0",
    val description: String? = null,
    val servers: List<String> = emptyList(),

    /**
     * What every endpoint requires unless it says otherwise. Documented, not
     * enforced: checking the token is a handler's or a filter's job.
     */
    val security: List<SecurityRequirement> = emptyList(),

    /**
     * Cross-origin access, off unless set. Unlike [security] this one is
     * enforced: the interpreters answer preflights and work out which methods
     * and headers to allow from the descriptions.
     */
    val cors: Cors? = null,

    /** How long to wait for a strict (non-streaming) request body. */
    val strictBodyTimeoutMillis: Long = 10_000,

    /**
     * The largest strict body that will be read; over it is a 413 raised before
     * any codec sees it. Defaulted because an unbounded body is a way to run a
     * service out of memory with one request.
     */
    val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,

    /**
     * Runs around every handler, outermost first, composed once at route-build
     * time. A check written here also covers the endpoint added next week,
     * which a check copied into each handler does not.
     */
    val filters: List<Filter> = emptyList(),

    /**
     * Puts an unexpected throwable's message back in the 500 body. Off: that
     * message is written for a log and may name a table, a host or a query.
     */
    val exposeInternalErrors: Boolean = false,

    /**
     * Called when a handler throws something nobody described, with the
     * reference printed in the response body. Null logs it instead.
     */
    val onServerError: ((reference: String, endpoint: Endpoint<*, *>?, error: Throwable) -> Unit)? = null,

    /**
     * Endpoints that must appear in [endpoints]. Nothing in the type system
     * says a list covers a set, so one left out is otherwise documented,
     * unrouted, and answering 404. Hand it the list the spec is built from.
     */
    val covers: List<Endpoint<*, *>> = emptyList(),

    /** The calls this service sends. Documented and generated, never routed. */
    val webhooks: List<Webhook> = emptyList(),
) {
    init {
        val bound = endpoints.map { it.endpoint }
        refuseWebhooksAmong(bound)
        refuseRepeatedWebhooks(webhooks)

        // The router stops at the first match, so the second is unreachable.
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
        webhooks = webhooks,
    )
}

/** Eight megabytes. See [Api.maxBodyBytes] for why there is a limit at all. */
private const val DEFAULT_MAX_BODY_BYTES: Long = 8L * 1024L * 1024L
