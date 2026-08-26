package io.github.matthewjones372.pelican

/**
 * The decoded inputs for one request, read back with the key objects declared
 * on the endpoint. The internal cast is unchecked but sound: a key enters the
 * map only through the decoder that produced its declared type.
 */
class Params(
    private val values: Map<ParamKey<*>, Any?>,
    /** The backend's own request object — Pekko's `HttpRequest`, Ktor's `ApplicationCall`. */
    val underlying: Any?,
    /** The description this request matched. Null only for a hand-built [Params]. */
    val endpoint: Endpoint<*, *>? = null,
    /**
     * What a caller reconnecting to an event stream said it had already seen.
     * Read by [lastEventId]; a request that is not one carries null, and so
     * does a fresh connect.
     */
    internal val resumeFrom: String? = null,
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

    @Suppress("DoubleMutabilityForCollection") // Null until first write is the point; see above.
    private var attributes: MutableMap<Attribute<*>, Any?>? = null

    /**
     * Reads what a filter worked out earlier. Throws if nothing set it: the
     * handler is relying on a filter having run, and a missing one is a
     * wiring mistake.
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
     * Sets a declared response header, encoded by its own codec. One the
     * endpoint never declared throws rather than shipping undocumented.
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
     * An undeclared header, for what no document should promise — an
     * `X-Debug-*`, something a proxy expects. Undocumented on purpose.
     */
    fun setRawHeader(name: String, value: String) {
        outgoing()[name] = value
    }

    /**
     * What the handler asked to be sent, in order. Put on whatever response
     * came back, errors included: a header set before a failure was deliberate.
     */
    fun responseHeaders(): List<Pair<String, String>> =
        outgoing?.map { it.key to it.value }.orEmpty()

    /**
     * The headers a required declaration promised but nobody set. Not policed —
     * failing the response would replace a wrong header with a 500 — but a
     * test can assert on it.
     */
    fun missingRequiredHeaders(): List<ResponseHeader<*>> =
        endpoint?.responseHeaders.orEmpty().filter { it.required && it.name !in outgoing.orEmpty() }
}

/**
 * Where a caller reconnecting to an event stream left off — the `Last-Event-ID`
 * it sent back — or null on a fresh connect.
 *
 * An extension rather than a declared input because resume is optional by
 * nature: a stream is answerable without it, and putting it in the endpoint's
 * signature would change the arity of every handler for the sake of something
 * most of them ignore. What to do with the value is the service's — Pelican
 * frames and delivers, and retention is not its business.
 */
fun Params.lastEventId(): String? = resumeFrom

/**
 * Thrown from a handler to produce a specific error status. Anything else that
 * escapes a handler becomes a 500.
 */
class ApiException(
    val status: Int,
    override val message: String,
    val detail: String? = null,
    /**
     * Headers to send with this failure. Undeclared by design: a failure raised
     * deep in a handler has no endpoint description to hand.
     */
    val headers: List<Pair<String, String>> = emptyList(),
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    init {
        // Built deep inside a handler from a status that is often computed, so
        // a throw naming it beats one from a backend's status registry later.
        checkStatus("ApiException", status, carriesBody = true)
    }
}

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
 * The request body was larger than [Api.maxBodyBytes], raised before it is
 * decoded. [detail] is for a limit that is not the API's own — a multipart
 * part declares its own bound, and naming the wrong number misleads a caller.
 */
class PayloadTooLarge(val limit: Long, detail: String? = null) :
    RuntimeException(detail ?: "Request body exceeds the configured limit of $limit bytes")

/**
 * Nothing this service describes answers this request: 405 where some other
 * method describes the path, 404 otherwise.
 *
 * A type of its own rather than an [ApiException] because the two are the same
 * response and different traffic. `notFound()` from a handler is the endpoint
 * doing its job and is already counted as a request; this is a request that
 * never reached one, and only this one belongs in [RefusalReason.UNMATCHED].
 */
class Unrouted(
    val status: Int,
    override val message: String,
    val detail: String? = null,
) : RuntimeException(message)

/**
 * The request body arrived under a media type no codec this endpoint declared
 * can read. Its own type for the same reason [Unrouted] is.
 */
class UnsupportedMediaType(val detail: String) : RuntimeException("Unsupported media type") {
    override val message: String get() = "Unsupported media type"
}
