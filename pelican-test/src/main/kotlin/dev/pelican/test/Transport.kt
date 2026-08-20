package dev.pelican.test

import dev.pelican.Method

/**
 * One HTTP exchange, described in terms core already owns.
 *
 * No Pekko, no `java.net.http` — which is the point. [ApiClient] builds these
 * from endpoint descriptions and reads the results back, so the transport
 * underneath can be an in-memory route invocation or a real socket without a
 * single assertion changing.
 */
class RequestSpec(
    val method: Method,
    /** Already percent-encoded, with a leading slash. */
    val path: String,
    val query: List<Pair<String, String>>,
    val headers: List<Pair<String, String>>,
    val body: String?,
) {
    /** The path with its query string, as it would appear on the request line. */
    val target: String
        get() = if (query.isEmpty()) path
        else path + "?" + query.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }

    /**
     * The same request with one query parameter left off.
     *
     * The typed form always supplies every declared input — `limit: Int` has
     * no way to say "absent" — so asserting that the *server* applies its own
     * default means building the call and then dropping the parameter.
     */
    fun withoutQuery(name: String): RequestSpec =
        RequestSpec(method, path, query.filterNot { it.first == name }, headers, body)

    /** The same request with one header left off — for asserting on what the server does without it. */
    fun withoutHeader(name: String): RequestSpec =
        RequestSpec(method, path, query, headers.filterNot { it.first.equals(name, true) }, body)

    /**
     * The same request carrying one more header. No description mentions
     * `Origin`, so a cross-origin call is a described call with the browser's
     * headers put back on by hand.
     */
    fun withHeader(name: String, value: String): RequestSpec =
        RequestSpec(method, path, query, headers.filterNot { it.first.equals(name, true) } + (name to value), body)

    /**
     * The same request sent with a different method — the `OPTIONS` preflight a
     * browser sends ahead of the call, chiefly.
     */
    fun withMethod(method: Method): RequestSpec = RequestSpec(method, path, query, headers, body)

    /**
     * The same request aimed at a different path. The typed form can only
     * produce paths that decode, so reaching a 400 or an unrouted 404 means
     * building a valid call and then breaking it on purpose.
     */
    fun withPath(path: String): RequestSpec = RequestSpec(method, path, query, headers, body)

    /** The same request carrying a different body — malformed input, chiefly. */
    fun withBody(body: String?): RequestSpec = RequestSpec(method, path, query, headers, body)

    override fun toString() = "$method $target"
}

class ResponseSpec(
    val status: Int,
    val headers: List<Pair<String, String>>,
    val body: String,
) {
    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** The media type without its parameters, e.g. `application/json`. */
    val contentType: String?
        get() = header("Content-Type")?.substringBefore(';')?.trim()

    val isSuccess: Boolean get() = status in 200..299

    override fun toString() = "$status ${body.take(200)}"
}

/**
 * Where a [RequestSpec] goes.
 *
 * Deliberately blocking. A test asserts on a result it already has; making the
 * suite thread `CompletionStage` through every assertion would buy nothing and
 * cost readability. Transports that are async underneath join here.
 */
interface Transport {
    fun send(request: RequestSpec): ResponseSpec
}

internal fun urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, Charsets.UTF_8)

/** Path segments are encoded more conservatively than query values. */
internal fun encodeSegment(s: String): String =
    urlEncode(s).replace("+", "%20")
