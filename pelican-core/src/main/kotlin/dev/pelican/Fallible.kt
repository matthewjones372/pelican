package dev.pelican

import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Phantom marker for "the handler produces [T], or one of the failures the
 * endpoint declared".
 *
 * Like [StreamOf] it has no instances. Its job is to put the declared error
 * type into `Endpoint<I, R>` without adding a third type parameter to every
 * signature in the library: an endpoint that declares failures is an
 * `Endpoint<I, Fallible<E, T>>`, and the binder for that shape demands a
 * handler returning [Outcome], so an undeclared failure does not compile.
 */
class Fallible<E, T> private constructor()

/**
 * What a fallible handler returns: the success value, or one of the declared
 * failures carrying its payload.
 *
 * Build the success side with [ok] and the failure side by invoking the
 * [ErrorOutput] itself — naming the failure is what fixes the status, so two
 * failures sharing a payload type stay distinguishable:
 *
 * ```
 * val badKey     = errorJson<ApiError>(401, "Missing or bad API key")
 * val noSuchUser = errorJson<ApiError>(404, "No user with that id")
 *
 * placeOrder handledNow { (id, key, req) ->
 *     when {
 *         key != expected      -> badKey(ApiError(401, "Bad API key"))
 *         Store.user(id) == null -> noSuchUser(ApiError(404, "No user $id"))
 *         else                 -> ok(Store.create(id, req))
 *     }
 * }
 * ```
 *
 * A failure that declares headers is invoked with their values as well; see
 * [ErrorOutput.invoke].
 */
sealed interface Outcome<out E, out T> {
    data class Ok<T>(val value: T) : Outcome<Nothing, T>

    /** [declared] is the failure the endpoint listed; it supplies the status. */
    data class Err<E>(
        val declared: ErrorOutput<E>,
        val error: E,
        /**
         * The headers [declared] carries, already encoded, in the order it
         * declared them.
         *
         * One field, written by [ErrorOutput.invoke] on the way out and filled
         * from the response on the way back in — so the value a handler sent
         * and the value a client reads are the same field of the same type,
         * rather than two readings that could disagree.
         */
        val headers: List<Pair<String, String>> = emptyList(),
    ) : Outcome<E, Nothing> {
        /**
         * One header back, decoded by its own codec:
         *
         * ```
         * val refused = app.outcome(placeOrder, input) as Outcome.Err
         * refused[retryAfter]        // Long?
         * ```
         *
         * Null when it did not arrive. Nullable rather than throwing because
         * this is also what a *client* reads: a server that promised a header
         * and left it off is a finding for the test to make, not a reason to
         * lose the failure that did arrive.
         */
        @Suppress("UNCHECKED_CAST")
        operator fun <T : Any> get(header: ResponseHeader<T>): T? =
            headers.firstOrNull { (name, _) -> name.equals(header.name, ignoreCase = true) }
                ?.let { (_, raw) -> header.codec.decode(header.name, raw) as T }
    }
}

fun <T> ok(value: T): Outcome<Nothing, T> = Outcome.Ok(value)

/**
 * A declared header paired with the value one failure is sending it with —
 * `retryAfter of 30L`. See [ErrorOutput.invoke].
 */
class HeaderValue internal constructor(
    internal val header: ResponseHeader<*>,
    private val value: Any,
) {
    /** By the header's own codec, so the wire carries what the schema describes. */
    @Suppress("UNCHECKED_CAST")
    internal fun encoded(): String = (header.codec as PlainCodec<Any>).encode(value)
}

/**
 * Supplies a declared failure's header, typed by the header's own declaration:
 * a `Retry-After` declared as a `Long` takes a `Long` and nothing else.
 *
 * An infix pair rather than a `Pair`, because `to` would type the value as
 * `Any` and let `retryAfter to "soon"` compile — which is the whole of what
 * declaring the header was for.
 */
infix fun <T : Any> ResponseHeader<T>.of(value: T): HeaderValue = HeaderValue(this, value)

/**
 * One declared failure: a status, a payload type, what it means, and the
 * headers it sends alongside the payload.
 */
class ErrorOutput<E> @PublishedApi internal constructor(
    val status: Int,
    val type: KType,
    val description: String,
    /**
     * Declared here rather than with `emits(...)`, which is the *success*
     * response's list: a `Retry-After` named there would be documented on the
     * 200 and permitted on every response the endpoint sends, which is exactly
     * how one ends up on a success nobody meant to throttle.
     */
    val headers: List<ResponseHeader<*>> = emptyList(),
) {
    init {
        val clashes = headers.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.keys
        require(clashes.isEmpty()) { "error:$status declares the header(s) $clashes more than once" }
    }

    /**
     * Produces this failure, with a value for each header it declared:
     *
     * ```
     * val throttled = errorJson<ApiError>(429, "Too many requests", retryAfter)
     *
     * throttled(ApiError(429, "Slow down"), retryAfter of 30L)
     * ```
     *
     * A failure declaring no headers is invoked as it always was, with the
     * payload alone.
     *
     * The payload type is checked against the declaration, and so are the
     * headers: one this failure never declared, or a required one left out,
     * throws here. That is stricter than [Params.setHeader], which reports a
     * missing required header rather than failing on it — and it can be,
     * because the two cases are not alike. A handler setting headers one at a
     * time is never finished until the response is built, so nothing can tell
     * mid-handler whether a promise is broken or merely not kept yet; this
     * call *is* the whole answer, so everything needed to tell is in hand.
     */
    operator fun invoke(error: E, vararg values: HeaderValue): Outcome<E, Nothing> =
        Outcome.Err(this, error, encode(values))

    /**
     * In declaration order rather than the order the call happened to list
     * them, so two handlers returning the same failure put the same response
     * on the wire.
     */
    private fun encode(supplied: Array<out HeaderValue>): List<Pair<String, String>> {
        supplied.forEach { given ->
            if (headers.none { it === given.header }) {
                error(
                    "${given.header.name} was sent with $this, which never declared it. " +
                        "List it on errorJson(...) beside the status, or set it with setHeader " +
                        "if it belongs on every response this endpoint sends.",
                )
            }
        }
        return headers.mapNotNull { declared ->
            val given = supplied.firstOrNull { it.header === declared }
            when {
                given != null -> declared.name to given.encoded()

                declared.required -> error(
                    "$this declares ${declared.name} and this call left it out. " +
                        "Pass ${declared.name} with the payload, or declare it as " +
                        "responseHeader(...).optional() if it is only sometimes sent.",
                )

                else -> null
            }
        }
    }

    internal fun spec() = ErrorSpec(status, description, type, headers)

    override fun toString() = "error:$status"
}

/**
 * Declares a failure response carrying [E] as a JSON body, outside an endpoint
 * block so a handler can name it. The same function exists on
 * [EndpointBuilder] for failures declared inline.
 *
 * Any [headers] listed here are documented on that response and are the only
 * ones the failure may be sent with.
 */
inline fun <reified E> errorJson(
    status: Int,
    description: String,
    vararg headers: ResponseHeader<*>,
): ErrorOutput<E> = ErrorOutput(status, typeOf<E>(), description, headers.toList())

/**
 * A success output paired with the failures a handler may return instead.
 *
 * Wrapping rather than replacing keeps every existing output usable on the
 * success side — including the streaming ones, so `Fallible<E, StreamOf<T>>`
 * means "a stream of T, or a failure decided before the first element".
 */
class FallibleOutput<E, T> internal constructor(
    val success: Output<T>,
    val failures: List<ErrorOutput<E>>,
) : Output<Fallible<E, T>>() {
    override val status get() = success.status
    override val mediaType get() = success.mediaType
    override val payloadType get() = success.payloadType

    init {
        require(failures.isNotEmpty()) { "orFail needs at least one declared failure" }
        val clashes = failures.groupBy { it.status }.filterValues { it.size > 1 }.keys
        require(clashes.isEmpty()) { "Two failures declared for status $clashes on the same output" }
    }
}

/** Declares the one failure this output's handler may return instead. */
infix fun <E, T> Output<T>.orFail(failure: ErrorOutput<E>): FallibleOutput<E, T> =
    declareFailures(listOf(failure))

/**
 * Declares the failures this output's handler may return instead. With several
 * payload types, [E] infers to their common supertype — a sealed hierarchy of
 * problems being the case worth aiming for, since the handler's `when` over it
 * is then exhaustive.
 */
fun <E, T> Output<T>.orFail(vararg failures: ErrorOutput<out E>): FallibleOutput<E, T> =
    declareFailures(failures.toList())

/** Both spellings of `orFail` end here, so they cannot disagree about what one means. */
private fun <E, T> Output<T>.declareFailures(failures: List<ErrorOutput<out E>>): FallibleOutput<E, T> {
    require(this !is FallibleOutput<*, *>) { "orFail is already applied to $this" }
    @Suppress("UNCHECKED_CAST")
    return FallibleOutput(this, failures as List<ErrorOutput<E>>)
}
