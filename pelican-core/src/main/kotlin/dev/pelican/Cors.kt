package dev.pelican

/**
 * Which origins a browser may call this API from.
 *
 * A value, like everything else here, so the same setting can be shared
 * between environments and asserted on in a test.
 */
sealed interface CorsOrigins {
    fun allows(origin: String): Boolean

    /** `Access-Control-Allow-Origin: *`. No credentials — see [Cors]. */
    data object Any : CorsOrigins {
        override fun allows(origin: String) = true
    }

    /** An exact list. Compared as written: scheme, host and port all have to agree. */
    class Listed(val origins: Set<String>) : CorsOrigins {
        init { require(origins.isNotEmpty()) { "Listed CORS origins cannot be empty" } }
        override fun allows(origin: String) = origin in origins
        override fun toString() = origins.joinToString(", ")
    }

    /**
     * Anything a predicate accepts — subdomains, a per-tenant scheme, a list
     * read from configuration at startup. [description] is what a failure
     * message says was expected.
     */
    class Matching(val description: String, val predicate: (String) -> Boolean) : CorsOrigins {
        override fun allows(origin: String) = predicate(origin)
        override fun toString() = description
    }
}

/**
 * The CORS settings for an [Api]. Off unless you set one: an API with no `cors`
 * emits no `Access-Control-*` header and answers no preflight, exactly as
 * before this existed.
 *
 * What a browser is told is derived from the endpoint descriptions rather than
 * configured a second time. The methods a preflight allows are the methods
 * declared on that path; the headers it allows are the ones the endpoints on
 * that path declare, plus `Content-Type` where they take a body and the
 * credential header their security scheme names. So a new endpoint, a new
 * `header(...)` or a new scheme is reachable from the browser as soon as it is
 * described — there is no second list to keep in step.
 *
 * ```
 * Api(routes, JacksonCodecs, cors = cors("https://app.example.com"))
 * ```
 *
 * [additionalAllowedHeaders] covers what a description cannot know about — a
 * tracing header added by a gateway, say. It adds to the derived set rather
 * than replacing it, because a list shorter than what your own endpoints
 * declare is a list that breaks them.
 *
 * [exposedHeaders] is the other direction: response headers a script is allowed
 * to read. Pelican does not describe response headers, so this one is a plain
 * list.
 *
 * Credentials and `*` cannot be combined — the browser rejects that pairing, so
 * it fails here instead, when the value is built.
 */
class Cors internal constructor(
    val origins: CorsOrigins,
    val additionalAllowedHeaders: List<String> = emptyList(),
    val exposedHeaders: List<String> = emptyList(),
    val allowCredentials: Boolean = false,
    /** How long a browser may cache a preflight. Null leaves the header off. */
    val maxAgeSeconds: Long? = 600,
) {
    init {
        require(!(origins is CorsOrigins.Any && allowCredentials)) {
            "A browser refuses `Access-Control-Allow-Origin: *` together with credentials. " +
                "List the origins that may send them."
        }
    }
}

/**
 * CORS for the origins named, which is the setting a service in front of a
 * single-page app wants:
 *
 * ```
 * cors("https://app.example.com", "http://localhost:5173")
 * ```
 */
fun cors(
    vararg origins: String,
    additionalAllowedHeaders: List<String> = emptyList(),
    exposedHeaders: List<String> = emptyList(),
    allowCredentials: Boolean = false,
    maxAgeSeconds: Long? = 600,
): Cors = cors(
    origins = CorsOrigins.Listed(origins.toSet()),
    additionalAllowedHeaders = additionalAllowedHeaders,
    exposedHeaders = exposedHeaders,
    allowCredentials = allowCredentials,
    maxAgeSeconds = maxAgeSeconds,
)

/** CORS for any [CorsOrigins] — [CorsOrigins.Any], or a predicate of your own. */
fun cors(
    origins: CorsOrigins,
    additionalAllowedHeaders: List<String> = emptyList(),
    exposedHeaders: List<String> = emptyList(),
    allowCredentials: Boolean = false,
    maxAgeSeconds: Long? = 600,
): Cors = Cors(
    origins = origins,
    additionalAllowedHeaders = additionalAllowedHeaders,
    exposedHeaders = exposedHeaders,
    allowCredentials = allowCredentials,
    maxAgeSeconds = maxAgeSeconds,
)

/**
 * CORS for every origin. A public read-only API; not one that reads a cookie or
 * an `Authorization` header a browser sends automatically — for those, name the
 * origins.
 */
fun corsAnyOrigin(
    additionalAllowedHeaders: List<String> = emptyList(),
    exposedHeaders: List<String> = emptyList(),
    maxAgeSeconds: Long? = 600,
): Cors = cors(
    origins = CorsOrigins.Any,
    additionalAllowedHeaders = additionalAllowedHeaders,
    exposedHeaders = exposedHeaders,
    allowCredentials = false,
    maxAgeSeconds = maxAgeSeconds,
)

// --------------------------------------------------------------------- policy

/** What a backend should do with one `OPTIONS` request. */
sealed interface CorsPreflight {
    /**
     * Not a preflight at all — no `Origin`, no `Access-Control-Request-Method`,
     * or no endpoint on this path. The backend carries on as it would have
     * without CORS, which usually means 404 or 405.
     */
    data object NotPreflight : CorsPreflight

    /** Answer 204 with these headers, and no body. */
    class Allowed(val headers: List<Pair<String, String>>) : CorsPreflight

    /** Answer 403. [reason] is the detail, and says which check failed. */
    class Refused(val reason: String) : CorsPreflight
}

/**
 * The CORS decisions for one [Api], worked out from its descriptions.
 *
 * Built once when an interpreter binds the API, not per request: the answer to
 * a preflight depends on the path and the origin, and everything else about it
 * — which methods, which headers — is settled by the descriptions before a
 * request arrives.
 *
 * Backends use exactly two entry points, which is what keeps CORS one
 * implementation rather than three: [actualResponseHeaders] for a real request,
 * and [preflight] for an `OPTIONS` one.
 */
class CorsPolicy internal constructor(
    val cors: Cors,
    private val endpoints: List<Endpoint<*, *>>,
    private val apiSecurity: List<SecurityRequirement>,
) {
    /**
     * The headers to add to a real response, given the request's `Origin`.
     *
     * No `Access-Control-Allow-Origin` for a request with no `Origin` — a curl,
     * a server-to-server call, a same-origin fetch — and none for an origin the
     * policy does not allow, which is what makes the browser block the
     * response.
     *
     * `Vary: Origin` is still returned in both of those cases. The header is a
     * statement about the *route*, not about this one request: any response
     * here could have carried a different `Access-Control-Allow-Origin`, so a
     * shared cache must key on `Origin` or it will hand a browser the
     * header-less answer it stored for a curl.
     */
    fun actualResponseHeaders(origin: String?): List<Pair<String, String>> {
        val allowed = allowOriginHeader(origin) ?: return listOfNotNull(varyOrigin())

        val headers = mutableListOf(allowed)
        if (cors.allowCredentials) headers += ALLOW_CREDENTIALS to "true"
        if (cors.exposedHeaders.isNotEmpty()) {
            headers += EXPOSE_HEADERS to cors.exposedHeaders.joinToString(", ")
        }
        varyOrigin()?.let { headers += it }
        return headers
    }

    /**
     * Decides one `OPTIONS` request.
     *
     * [path] is the request path, matched against the declared templates: the
     * methods offered back are the ones described for that path, so a preflight
     * cannot advertise a route that does not exist or omit one that does.
     */
    fun preflight(origin: String?, requestMethod: String?, path: String): CorsPreflight {
        if (origin == null || requestMethod == null) return CorsPreflight.NotPreflight

        val onPath = endpoints.filter { it.pathSpec.matchesPath(path) }
        if (onPath.isEmpty()) return CorsPreflight.NotPreflight

        if (!cors.origins.allows(origin)) {
            return CorsPreflight.Refused("Origin '$origin' is not allowed; this API allows ${cors.origins}")
        }

        val methods = onPath.map { it.method.name }.distinct()
        val asked = requestMethod.trim().uppercase()
        if (asked !in methods) {
            return CorsPreflight.Refused(
                "Method '$asked' is not allowed on $path; it allows ${methods.joinToString(", ")}",
            )
        }

        // Headers are derived from the endpoints for the method being asked
        // about, so a preflight for GET is not told about the body header a
        // POST on the same path declares.
        val forMethod = onPath.filter { it.method.name == asked }
        val headers = mutableListOf(checkNotNull(allowOriginHeader(origin)))
        if (cors.allowCredentials) headers += ALLOW_CREDENTIALS to "true"
        headers += ALLOW_METHODS to methods.joinToString(", ")

        val allowedHeaders = allowedRequestHeaders(forMethod)
        if (allowedHeaders.isNotEmpty()) headers += ALLOW_HEADERS to allowedHeaders.joinToString(", ")

        cors.maxAgeSeconds?.let { headers += MAX_AGE to it.toString() }
        varyOrigin()?.let { headers += it }

        // `Access-Control-Request-Headers` is deliberately not consulted. What
        // is allowed is what the endpoints on this path declare, whatever the
        // browser asks for, so a header nobody described is simply absent from
        // the answer rather than turning the preflight into a 403 the browser
        // reports as a bare network error.
        return CorsPreflight.Allowed(headers)
    }

    /**
     * The request headers a browser may send to [endpoints], read off their
     * descriptions.
     *
     * Public because it is the answer to "why is my header being stripped" —
     * assert on it in a test rather than reading a preflight by hand.
     */
    fun allowedRequestHeaders(endpoints: List<Endpoint<*, *>>): List<String> {
        val names = LinkedHashSet<String>()

        for (ep in endpoints) {
            ep.headerParams.forEach { names += it.name }
            // Deliberately not ep.cookieParams. `Cookie` is a forbidden header
            // name: a script cannot set it, the browser attaches it itself, and
            // whether it does is `allowCredentials` rather than this list. A
            // cookie parameter named here would be a permission granted for
            // something nobody was going to ask permission for.
            if (ep.bodyInput != null) names += "Content-Type"
            (ep.security ?: apiSecurity).forEach { requirement ->
                credentialHeaderOf(requirement.scheme)?.let { names += it }
            }
        }
        cors.additionalAllowedHeaders.forEach { names += it }

        // Two spellings of one header name would have a browser match neither
        // reliably, so the first spelling declared wins.
        return names.distinctBy { it.lowercase() }
    }

    private fun allowOriginHeader(origin: String?): Pair<String, String>? = when {
        cors.origins is CorsOrigins.Any && !cors.allowCredentials -> ALLOW_ORIGIN to "*"
        origin == null -> null
        cors.origins.allows(origin) -> ALLOW_ORIGIN to origin
        else -> null
    }

    /**
     * A response that would have been different for another origin cannot be
     * cached under one key — including the response that carries no
     * `Access-Control-*` at all, which is a different answer too. `*` is the
     * same answer for everyone, so it needs no `Vary`.
     */
    private fun varyOrigin(): Pair<String, String>? =
        if (cors.origins is CorsOrigins.Any && !cors.allowCredentials) null else "Vary" to "Origin"
}

/**
 * The CORS decisions for this API, or null when it has none configured.
 *
 * Called once by an interpreter as it binds the endpoints — see the three
 * backends. Nothing about it is per-request.
 */
fun Api.corsPolicy(): CorsPolicy? =
    cors?.let { CorsPolicy(it, endpoints.map { se -> se.endpoint }, security) }

/**
 * The header a browser would carry this scheme's credential in, or null when it
 * is not one a script sets — a cookie the browser attaches itself, a query
 * parameter that is part of the URL.
 */
private fun credentialHeaderOf(scheme: SecurityScheme): String? = when (scheme) {
    is HttpScheme -> "Authorization"
    is ApiKeyScheme -> scheme.paramName.takeIf { scheme.location == "header" }
    is OAuth2Scheme, is OpenIdConnectScheme -> "Authorization"
}

/**
 * Whether a concrete request path matches this template.
 *
 * Literals have to agree; a capture takes whatever is in that position. Only
 * the shape is checked — whether the capture *decodes* is the interpreter's
 * business, and a preflight is answered before anything decodes anyway.
 */
internal fun PathSpec.matchesPath(path: String): Boolean {
    val parts = path.split('/').filter { it.isNotEmpty() }
    if (parts.size != segments.size) return false
    return segments.withIndex().all { (i, segment) ->
        when (segment) {
            is PathSegment.Literal -> segment.value == parts[i]
            is PathSegment.Capture -> true
        }
    }
}

private const val ALLOW_ORIGIN = "Access-Control-Allow-Origin"
private const val ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials"
private const val ALLOW_METHODS = "Access-Control-Allow-Methods"
private const val ALLOW_HEADERS = "Access-Control-Allow-Headers"
private const val EXPOSE_HEADERS = "Access-Control-Expose-Headers"
private const val MAX_AGE = "Access-Control-Max-Age"

/** The request headers a backend reads to answer a preflight. */
object CorsHeaders {
    const val ORIGIN = "Origin"
    const val REQUEST_METHOD = "Access-Control-Request-Method"
}
