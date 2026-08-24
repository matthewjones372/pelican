package dev.pelican

import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Phantom marker for "the handler names which of this endpoint's declared
 * responses it is producing" — one of the successes carrying [T], or one of
 * the failures carrying [E].
 *
 * Like [StreamOf] it has no instances. Its job is to put the declared types
 * into `Endpoint<I, R>` without adding a third type parameter to every
 * signature in the library: an endpoint whose output names alternatives is an
 * `Endpoint<I, Fallible<E, T>>`, and the binders for that shape demand a
 * handler returning [Outcome], so a response the endpoint never declared does
 * not compile.
 *
 * It is still called `Fallible` although failures are now one kind of
 * alternative rather than the only kind. The name is in every published binder
 * signature and in every `Endpoint<I, Fallible<E, T>>` anyone has written down;
 * a rename would have bought a better word for the `E = Nothing` case at the
 * cost of two names for one type forever, and the aliases would have outlived
 * anyone's memory of why there were two.
 */
class Fallible<E, T> private constructor()

/**
 * What a handler for such an endpoint returns: one of the declared responses,
 * carrying its payload.
 *
 * Build it by invoking the declaration itself — naming the response is what
 * fixes the status, so two responses sharing a payload type stay
 * distinguishable:
 *
 * ```
 * val badKey     = errorJson<ApiError>(401, "Missing or bad API key")
 * val noSuchUser = errorJson<ApiError>(404, "No user with that id")
 *
 * placeOrder handledOrFail { (id, key, req) ->
 *     when {
 *         key != expected        -> badKey(ApiError(401, "Bad API key"))
 *         Store.user(id) == null -> noSuchUser(ApiError(404, "No user $id"))
 *         else                   -> ok(Store.create(id, req))
 *     }
 * }
 * ```
 *
 * The success side works the same way once there is more than one of it:
 * [ok] means the first declared success, and any of them can be named instead
 * by invoking it. See [Output.invoke].
 *
 * A response that declares headers is invoked with their values as well; see
 * [Output.invoke] and [ErrorOutput.invoke].
 */
sealed interface Outcome<out E, out T> {
    /**
     * One of the declared successes.
     *
     * [declared] is which one, and null means the first — that is what [ok]
     * produces, and with a single declared success it is the only one there
     * is. Naming it is how an endpoint declaring `200 Order` beside
     * `201 Order` says which of the two this is, since the payload type cannot.
     */
    data class Ok<T>(
        val value: T,
        val declared: Output<T>? = null,
        /** As [Err.headers]: encoded on the way out, filled from the response on the way back. */
        val headers: List<Pair<String, String>> = emptyList(),
    ) : Outcome<Nothing, T> {
        /** One header back, decoded by its own codec. Null when it did not arrive. */
        operator fun <H : Any> get(header: ResponseHeader<H>): H? = headerValue(headers, header)
    }

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
        operator fun <T : Any> get(header: ResponseHeader<T>): T? = headerValue(headers, header)
    }
}

/**
 * Shared by both sides of an [Outcome], because "read one declared header back
 * off this response" is one question and two readings of it would be two
 * answers.
 */
@Suppress("UNCHECKED_CAST")
private fun <T : Any> headerValue(headers: List<Pair<String, String>>, header: ResponseHeader<T>): T? =
    headers.firstOrNull { (name, _) -> name.equals(header.name, ignoreCase = true) }
        ?.let { (_, raw) -> header.codec.decode(header.name, raw) as T }

/** The first declared success, carrying [value]. */
fun <T> ok(value: T): Outcome<Nothing, T> = Outcome.Ok(value)

/**
 * A declared header paired with the value one response is sending it with —
 * `retryAfter of 30L`. See [ErrorOutput.invoke] and [Output.invoke].
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
 * Supplies a declared response's header, typed by the header's own declaration:
 * a `Retry-After` declared as a `Long` takes a `Long` and nothing else.
 *
 * An infix pair rather than a `Pair`, because `to` would type the value as
 * `Any` and let `retryAfter to "soon"` compile — which is the whole of what
 * declaring the header was for.
 */
infix fun <T : Any> ResponseHeader<T>.of(value: T): HeaderValue = HeaderValue(this, value)

/**
 * The headers one declared response is being sent with, checked against what it
 * declared and encoded in *declaration* order rather than the order this call
 * happened to list them — so two handlers producing the same response put the
 * same bytes on the wire.
 *
 * One function for successes and failures alike. The bargain is identical on
 * both sides, and the day it was written twice is the day a `Location` on a 201
 * would have been checked differently from a `Retry-After` on a 429.
 *
 * [owner] appears in the messages and is the declaration itself, so a refusal
 * names the response rather than the status alone.
 */
internal fun encodeDeclaredHeaders(
    owner: Any,
    declared: List<ResponseHeader<*>>,
    supplied: Array<out HeaderValue>,
): List<Pair<String, String>> {
    supplied.forEach { given ->
        if (declared.none { it === given.header }) {
            error(
                "${given.header.name} was sent with $owner, which never declared it. " +
                    "List it on the response beside its status, or set it with setHeader " +
                    "if it belongs on every response this endpoint sends.",
            )
        }
    }
    return declared.mapNotNull { header ->
        val given = supplied.firstOrNull { it.header === header }
        when {
            given != null -> header.name to given.encoded()

            header.required -> error(
                "$owner declares ${header.name} and this call left it out. " +
                    "Pass ${header.name} with the payload, or declare it as " +
                    "responseHeader(...).optional() if it is only sometimes sent.",
            )

            else -> null
        }
    }
}

/**
 * One declared failure: a status, a payload type, what it means, and the
 * headers it sends alongside the payload.
 */
class ErrorOutput<E> @PublishedApi internal constructor(
    val status: Int,
    val type: KType,
    val description: String,
    /**
     * Declared here rather than with `emits(...)`, which is the *endpoint's*
     * list: a `Retry-After` named there would be documented on every response
     * and permitted on every response the endpoint sends, which is exactly how
     * one ends up on a success nobody meant to throttle.
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
     * The headers are checked against the declaration: one this failure never
     * declared, or a required one left out, throws here. That is stricter than
     * [Params.setHeader], which reports a missing required header rather than
     * failing on it — and it can be, because the two cases are not alike. A
     * handler setting headers one at a time is never finished until the
     * response is built, so nothing can tell mid-handler whether a promise is
     * broken or merely not kept yet; this call *is* the whole answer, so
     * everything needed to tell is in hand.
     */
    operator fun invoke(error: E, vararg values: HeaderValue): Outcome<E, Nothing> =
        Outcome.Err(this, error, encodeDeclaredHeaders(this, headers, values))

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
 * The responses one endpoint declares: at least one success, and the failures a
 * handler may return instead.
 *
 * Wrapping rather than replacing keeps every existing output usable as a
 * success — including the streaming ones, so `Fallible<E, StreamOf<T>>` still
 * means "a stream of T, or a failure decided before the first element".
 */
class FallibleOutput<E, T> internal constructor(
    /** In declaration order. The first is the one a bare [ok] means. */
    val successes: List<Output<out T>>,
    val failures: List<ErrorOutput<E>>,
) : Output<Fallible<E, T>>() {
    /**
     * The first declared success — what a single-success endpoint has always
     * had, and what the status, media type and payload type below report.
     */
    val success: Output<out T> get() = successes.first()

    override val status get() = success.status
    override val mediaType get() = success.mediaType
    override val payloadType get() = success.payloadType

    init {
        require(successes.isNotEmpty()) { "An output declares at least one success" }

        // Told apart by status and by nothing else: a client reading a
        // response has the status and the bytes, and two 2xx sharing a status
        // is a pair no reader could separate — including this library's own
        // test client and generated client, which match on it.
        val clashes = (successes.map { it.status } + failures.map { it.status })
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        require(clashes.isEmpty()) {
            "Two responses are declared for status ${clashes.joinToString()} on the same output. " +
                "An endpoint answers one status one way; give them different statuses, or declare one."
        }

        // A streamed alternative would have to be *produced* by naming it, and
        // producing a stream means handing over the backend's own type — a
        // Source, a Flow, a Sequence — which core cannot name. The alternative
        // would be an `invoke` per backend with the element type unchecked,
        // which is three copies of the one thing the phantom marker exists to
        // avoid. A stream is still a success; it is just the only one.
        if (successes.size > 1) {
            val streamed = successes.filter { it.streams() }
            require(streamed.isEmpty()) {
                "${streamed.joinToString()} streams, and an endpoint that declares several successes " +
                    "cannot stream one of them: naming a response is what produces it, and a stream is " +
                    "produced in the backend's own type. Declare the stream as the one success, or answer " +
                    "the other statuses from a different endpoint."
            }
        }
    }

    override fun toString() =
        "responses(${(successes + failures).joinToString { it.toString() }})"
}

/** Whether producing this output means handing over a stream rather than a value. */
private fun Output<*>.streams(): Boolean =
    this is NdjsonOutput<*> || this is SseOutput<*> || this is JsonArrayOutput<*> || this is ByteStreamOutput

/**
 * Declares a second successful response beside this one:
 *
 * ```
 * json<Order>(status = 201) or empty(status = 202)
 * ```
 *
 * [T] infers to the two payload types' common supertype, so a sealed hierarchy
 * is the case worth aiming for — a handler's `when` over the result is then
 * exhaustive, and so is a caller's. With unrelated types it is `Any`, and the
 * statuses are still what tell the responses apart on the wire.
 */
infix fun <T> Output<out T>.or(other: Output<out T>): FallibleOutput<Nothing, T> =
    responses(listOf(this, other), emptyList())

/** Three or more, declared in one call rather than chained. */
fun <T> Output<out T>.or(vararg others: Output<out T>): FallibleOutput<Nothing, T> =
    responses(listOf(this) + others, emptyList())

/**
 * Another success on an output that already names alternatives, so `a or b or c`
 * keeps the payload type the first two agreed on.
 *
 * The receiver is the more specific of the two `or`s, so Kotlin picks this one
 * whenever the third response's payload still fits — and where it does not,
 * the general one above takes over and [T] widens to what they have in common.
 * Either way the alternatives end up in one list; see [responses].
 */
infix fun <E, T> FallibleOutput<E, T>.or(other: Output<out T>): FallibleOutput<E, T> =
    responses(listOf(this, other), emptyList())

/** Declares the one failure this output's handler may return instead. */
infix fun <E, T> Output<T>.orFail(failure: ErrorOutput<E>): FallibleOutput<E, T> =
    orFailAll(listOf(this), listOf(failure))

/**
 * Declares the failures this output's handler may return instead. With several
 * payload types, [E] infers to their common supertype — a sealed hierarchy of
 * problems being the case worth aiming for, since the handler's `when` over it
 * is then exhaustive.
 */
fun <E, T> Output<T>.orFail(vararg failures: ErrorOutput<out E>): FallibleOutput<E, T> =
    orFailAll(listOf(this), failures.toList())

/**
 * The same, on an output that already declares several successes:
 * `created or accepted orFail badKey`.
 *
 * Only on one that has no failures yet — `Nothing` is what [or] produces and
 * what nothing else does — so declaring failures stays a single statement and
 * [responses] still has something to refuse.
 */
infix fun <E, T> FallibleOutput<Nothing, T>.orFail(failure: ErrorOutput<E>): FallibleOutput<E, T> =
    orFailAll(listOf(this), listOf(failure))

/** As above, for several failures at once. */
fun <E, T> FallibleOutput<Nothing, T>.orFail(vararg failures: ErrorOutput<out E>): FallibleOutput<E, T> =
    orFailAll(listOf(this), failures.toList())

/** Every spelling of `orFail` ends here, so they cannot disagree about what one means. */
private fun <E, T> orFailAll(
    declared: List<Output<*>>,
    failures: List<ErrorOutput<out E>>,
): FallibleOutput<E, T> {
    require(failures.isNotEmpty()) { "orFail needs at least one declared failure" }
    return responses(declared, failures)
}

/**
 * The one place a list of declared responses is assembled.
 *
 * An output that already names alternatives is spliced rather than kept: `or`
 * picks the chaining overload only while the payload types line up, and a
 * `FallibleOutput` nested inside another would be a "response" whose payload is
 * a phantom marker — rendered by nothing, documented as nothing, and reached by
 * a handler that thought it had named the third alternative. Splicing makes the
 * two readings of `a or b or c` the same list, which is what a reader assumes
 * they already are.
 */
private fun <E, T> responses(
    declared: List<Output<*>>,
    added: List<ErrorOutput<out E>>,
): FallibleOutput<E, T> {
    val nested = declared.filterIsInstance<FallibleOutput<*, *>>()

    val already = nested.firstOrNull { it.failures.isNotEmpty() }
    require(added.isEmpty() || already == null) { "orFail is already applied to $already" }

    val successes = declared.flatMap { if (it is FallibleOutput<*, *>) it.successes else listOf(it) }
    val failures = nested.flatMap { it.failures } + added

    @Suppress("UNCHECKED_CAST")
    return FallibleOutput(successes as List<Output<out T>>, failures as List<ErrorOutput<E>>)
}
