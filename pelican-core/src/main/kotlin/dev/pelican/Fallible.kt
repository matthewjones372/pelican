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
 */
sealed interface Outcome<out E, out T> {
    data class Ok<T>(val value: T) : Outcome<Nothing, T>

    /** [declared] is the failure the endpoint listed; it supplies the status. */
    data class Err<E>(val declared: ErrorOutput<E>, val error: E) : Outcome<E, Nothing>
}

fun <T> ok(value: T): Outcome<Nothing, T> = Outcome.Ok(value)

/** One declared failure: a status, a payload type, and what it means. */
class ErrorOutput<E> @PublishedApi internal constructor(
    val status: Int,
    val type: KType,
    val description: String,
) {
    /** Produces this failure. The payload type is checked against the declaration. */
    operator fun invoke(error: E): Outcome<E, Nothing> = Outcome.Err(this, error)

    internal fun spec() = ErrorSpec(status, description, type)

    override fun toString() = "error:$status"
}

/**
 * Declares a failure response carrying [E] as a JSON body, outside an endpoint
 * block so a handler can name it. The same function exists on
 * [EndpointBuilder] for failures declared inline.
 */
inline fun <reified E> errorJson(status: Int, description: String): ErrorOutput<E> =
    ErrorOutput(status, typeOf<E>(), description)

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
    orFail(*arrayOf(failure))

/**
 * Declares the failures this output's handler may return instead. With several
 * payload types, [E] infers to their common supertype — a sealed hierarchy of
 * problems being the case worth aiming for, since the handler's `when` over it
 * is then exhaustive.
 */
fun <E, T> Output<T>.orFail(vararg failures: ErrorOutput<out E>): FallibleOutput<E, T> {
    require(this !is FallibleOutput<*, *>) { "orFail is already applied to $this" }
    @Suppress("UNCHECKED_CAST")
    return FallibleOutput(this, failures.toList() as List<ErrorOutput<E>>)
}
