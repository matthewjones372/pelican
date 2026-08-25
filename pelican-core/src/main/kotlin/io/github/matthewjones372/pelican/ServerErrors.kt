package io.github.matthewjones372.pelican

import java.util.concurrent.CompletionException

/**
 * A handler produced a response its endpoint never declared.
 *
 * Not something a request can provoke: it needs a handler returning an
 * `ErrorOutput` or an `Output` belonging to another endpoint, which the type
 * system permits whenever `E` has widened to a sealed supertype. So it is a bug
 * in the service rather than in a request, and it is deliberately *not* in the
 * table below: naming the endpoint and the responses it declared tells a caller
 * about the inside of a service, and `ClientContractTest` is explicit that this
 * is what a caller must not be handed. So it takes the ordinary unexpected
 * path — a reference in the body, the whole message to `onServerError`, and the
 * detail back only under `exposeInternalErrors`.
 *
 * The type is its own so that a handler's bookkeeping mistake can be told apart
 * from an arbitrary throwable by whatever is watching the hook.
 */
class UndeclaredResponse(message: String) : RuntimeException(message)

/**
 * What one throwable becomes on the wire, decided in core so the three backends
 * cannot drift.
 */
class RenderedError(
    val error: ApiError,
    val headers: List<Pair<String, String>>,
    /** Non-null when this was a 500 nobody described. Log it. */
    val unexpected: Throwable?,
    /** The id printed in the response body, to grep the log for. */
    val reference: String?,
)

/**
 * The failures this library describes, and what each one is told to a caller.
 * Null is the throwable nobody described: that answer is a 500 carrying a
 * reference, and minting the reference is [renderError]'s job rather than this
 * table's, because a status this table is asked for on its own must not consume
 * an id no log line will ever print.
 *
 * Written once and read twice — by [renderError], which needs the whole
 * response, and by [statusOfError], which needs only the number. Those two
 * answers have to agree: a metric that says 413 while the caller was sent a 400
 * is worse than no metric, and the way to be sure is to leave them nowhere to
 * disagree.
 */
private fun described(t: Throwable): ApiError? = when (t) {
    is ApiException -> ApiError(t.status, t.message, t.detail)
    is DecodeFailure -> ApiError(400, "Invalid parameter", t.message)
    is BodyDecodeFailure -> ApiError(400, "Malformed request body", t.message)
    is NotAcceptable -> ApiError(406, "Not acceptable", t.message)
    is PayloadTooLarge -> ApiError(413, "Payload too large", t.message)
    else -> null
}

/**
 * Decides the response for a throwable. [exposeInternalDetail] puts an
 * unexpected message back in the body — for a local run, not production.
 */
fun renderError(raw: Throwable, exposeInternalDetail: Boolean = false): RenderedError {
    val t = unwrapCompletion(raw)

    val described = described(t)
    if (described != null) {
        return RenderedError(
            described,
            // Only an ApiException carries headers of its own; the rest are
            // raised where no response was being described.
            if (t is ApiException) t.headers else emptyList(),
            unexpected = null,
            reference = null,
        )
    }

    val reference = newReference()
    return RenderedError(
        ApiError(
            INTERNAL_SERVER_ERROR,
            "Internal server error",
            if (exposeInternalDetail) t.toString() else "Reference: $reference",
        ),
        emptyList(),
        unexpected = t,
        reference = reference,
    )
}

/**
 * The status a throwable becomes, without building the body that would go with
 * it. What a filter wants when it is measuring rather than answering — see
 * [Endpoint.statusFor], which is the only caller that should need this.
 */
internal fun statusOfError(raw: Throwable): Int =
    described(unwrapCompletion(raw))?.status ?: INTERNAL_SERVER_ERROR

/** What anything nobody described becomes. */
private const val INTERNAL_SERVER_ERROR = 500

internal fun unwrapCompletion(t: Throwable): Throwable =
    if (t is CompletionException) t.cause?.let(::unwrapCompletion) ?: t else t

/**
 * Short, unique enough to find in a log, and not sequential — a counter would
 * tell a caller how much traffic the service takes.
 */
private fun newReference(): String =
    java.util.UUID.randomUUID().toString().replace("-", "").take(REFERENCE_LENGTH)

/** Long enough to be unique in a log, short enough to read out over a phone. */
private const val REFERENCE_LENGTH = 12
