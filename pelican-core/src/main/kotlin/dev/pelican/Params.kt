package dev.pelican

/**
 * The decoded inputs for one request, and the place a handler puts anything it
 * wants on the response besides the body.
 *
 * Read values back with the same key objects you declared on the endpoint:
 *
 * ```
 * val id: Long = params[userId]
 * val lim: Int? = params[limit]
 * ```
 *
 * The cast is unchecked internally but sound by construction: a key can only
 * enter the map through the decoder that produced its declared type.
 *
 * Every handler lambda has one of these as its receiver, whatever its input
 * style — so a typed handler can still reach [setHeader], [endpoint] and the
 * backend's own request without giving up its typed inputs.
 */
class Params(
    private val values: Map<ParamKey<*>, Any?>,
    /**
     * Escape hatch. The backend puts its own request object here — Pekko's
     * `HttpRequest`, Ktor's `ApplicationCall` — and offers a typed accessor for
     * it. Core stays unaware of what it is.
     */
    val underlying: Any?,
    /**
     * The description this request matched. Null only where there is no
     * endpoint to speak of — a hand-built [Params] in a test. Filters read it
     * to decide what a request needs.
     */
    val endpoint: Endpoint<*, *>? = null,
) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: ParamKey<T>): T {
        if (!values.containsKey(key)) {
            error(
                "$key was read but never declared on this endpoint. " +
                    "Register it with query(...)/header(...)/body(...) first.",
            )
        }
        return values[key] as T
    }

    operator fun contains(key: ParamKey<*>): Boolean = values.containsKey(key)

    /**
     * The whole bag, for interpreters that need to walk it rather than read
     * known keys — a client turning inputs back into a request, for one.
     */
    fun asMap(): Map<ParamKey<*>, Any?> = values

    // ---------------------------------------------------------- attributes

    // Created on first write rather than per request. Most requests never
    // touch either of these maps — no filter sets an attribute, no handler
    // sets a header — and two empty LinkedHashMaps per request was a
    // measurable part of what the interpreter allocates over a hand-written
    // route. See OverheadBenchmark, which measures allocation as well as time.
    //
    // Neither map was ever synchronised, and this does not change that: a
    // Params belongs to one request. Where that request crosses a thread —
    // a filter here, a handler there — the CompletionStage between them is
    // what publishes the writes, as it already had to for the contents.
    @Suppress("DoubleMutabilityForCollection") // Null until first write is the point; see above.
    private var attributes: MutableMap<Attribute<*>, Any?>? = null

    /**
     * Reads what a filter worked out earlier in this request. Throws if nothing
     * set it — a handler that reads an attribute is relying on a filter having
     * run, and a missing one is a wiring mistake worth hearing about.
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: Attribute<T>): T {
        val set = attributes
        if (set == null || !set.containsKey(key)) {
            error(
                "$key was read but nothing set it on this request. " +
                    "A filter that sets it has to be registered on the Api, and reach this endpoint.",
            )
        }
        return set[key] as T
    }

    /** As [get], but null rather than throwing when no filter set it. */
    @Suppress("UNCHECKED_CAST")
    fun <T> find(key: Attribute<T>): T? = attributes?.get(key) as T?

    operator fun <T> set(key: Attribute<T>, value: T) {
        val set = attributes ?: LinkedHashMap<Attribute<*>, Any?>().also { attributes = it }
        set[key] = value
    }

    operator fun contains(key: Attribute<*>): Boolean = attributes?.containsKey(key) == true

    // ------------------------------------------------------ response headers

    @Suppress("DoubleMutabilityForCollection") // As with `attributes`: allocated only if used.
    private var outgoing: MutableMap<String, String>? = null

    private fun outgoing(): MutableMap<String, String> =
        outgoing ?: LinkedHashMap<String, String>().also { outgoing = it }

    /**
     * Sets a declared response header. The value is encoded by the header's own
     * codec, so what goes on the wire is what the document's schema describes.
     *
     * Setting a header the endpoint never declared with `emits(...)` throws
     * here rather than quietly shipping an undocumented header.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> setHeader(header: ResponseHeader<T>, value: T) {
        val declared = endpoint?.responseHeaders
        if (declared != null && declared.none { it === header }) {
            error(
                "${header.name} was set but $endpoint never declared it. " +
                    "Add it with emits(${header.name.substringAfterLast('-')}) on the endpoint.",
            )
        }
        outgoing()[header.name] = (header.codec as PlainCodec<Any>).encode(value)
    }

    /**
     * An undeclared header, for the things no document should promise — a
     * one-off `X-Debug-*`, a header a proxy in front expects. Undocumented on
     * purpose; [setHeader] is the one to reach for otherwise.
     */
    fun setRawHeader(name: String, value: String) {
        outgoing()[name] = value
    }

    /**
     * What the handler asked to be sent, in the order it asked. Interpreters
     * read this after the handler completes and put it on whatever response
     * came back — including an error response, since a header set before a
     * failure was still deliberate.
     */
    fun responseHeaders(): List<Pair<String, String>> =
        outgoing?.map { it.key to it.value }.orEmpty()

    /**
     * The headers a required declaration promised but nobody set. Interpreters
     * do not police this — a promise broken on one code path is a bug in the
     * handler, and failing the response would replace a wrong header with a
     * 500 — but a test can assert on it.
     */
    fun missingRequiredHeaders(): List<ResponseHeader<*>> =
        endpoint?.responseHeaders.orEmpty().filter { it.required && it.name !in outgoing.orEmpty() }
}

/**
 * Thrown from a handler to produce a specific error status. Anything else that
 * escapes a handler becomes a 500.
 */
class ApiException(
    val status: Int,
    override val message: String,
    val detail: String? = null,
    /**
     * Headers to send with this failure — `Retry-After` on a 429 or a 503,
     * `WWW-Authenticate` on a 401. Undeclared by design: a failure raised deep
     * in a handler has no endpoint description to hand.
     */
    val headers: List<Pair<String, String>> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)

fun badRequest(message: String): Nothing = throw ApiException(400, message)
fun notFound(message: String = "Not found"): Nothing = throw ApiException(404, message)
fun conflict(message: String): Nothing = throw ApiException(409, message)

fun unauthorized(message: String = "Unauthorized", challenge: String? = null): Nothing =
    throw ApiException(
        401,
        message,
        headers = listOfNotNull(challenge?.let { "WWW-Authenticate" to it }),
    )

fun forbidden(message: String = "Forbidden"): Nothing = throw ApiException(403, message)

/** Rejects a caller who is being asked to come back later. */
fun tooManyRequests(message: String = "Too many requests", retryAfterSeconds: Long? = null): Nothing =
    throw ApiException(
        429,
        message,
        headers = listOfNotNull(retryAfterSeconds?.let { "Retry-After" to it.toString() }),
    )

/**
 * The request body was larger than [Api.maxBodyBytes]. Raised by the
 * interpreters before the body is decoded, so an oversized payload never
 * reaches a codec.
 */
class PayloadTooLarge(val limit: Long) :
    RuntimeException("Request body exceeds the configured limit of $limit bytes")
