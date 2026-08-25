package io.github.matthewjones372.pelican

import java.util.concurrent.CompletionException

/**
 * What one throwable becomes on the wire, decided in core so the three backends
 * cannot drift.
 *
 * [unexpected] is the distinction that matters. A [DecodeFailure]'s message was
 * written for the caller; anything escaping a handler was written for a log and
 * may name a table or a host. So an unexpected failure carries only a
 * [reference] out, and the throwable goes back for the interpreter to log.
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
 * Decides the response for a throwable. [exposeInternalDetail] puts an
 * unexpected message back in the body — for a local run, not production.
 */
fun renderError(raw: Throwable, exposeInternalDetail: Boolean = false): RenderedError {
    val t = unwrapCompletion(raw)
    return when (t) {
        is ApiException -> RenderedError(
            ApiError(t.status, t.message, t.detail),
            t.headers,
            unexpected = null,
            reference = null,
        )

        is DecodeFailure -> RenderedError(
            ApiError(400, "Invalid parameter", t.message),
            emptyList(),
            unexpected = null,
            reference = null,
        )

        is BodyDecodeFailure -> RenderedError(
            ApiError(400, "Malformed request body", t.message),
            emptyList(),
            unexpected = null,
            reference = null,
        )

        is NotAcceptable -> RenderedError(
            ApiError(406, "Not acceptable", t.message),
            emptyList(),
            unexpected = null,
            reference = null,
        )

        is PayloadTooLarge -> RenderedError(
            ApiError(413, "Payload too large", t.message),
            emptyList(),
            unexpected = null,
            reference = null,
        )

        else -> {
            val reference = newReference()
            RenderedError(
                ApiError(
                    500,
                    "Internal server error",
                    if (exposeInternalDetail) t.toString() else "Reference: $reference",
                ),
                emptyList(),
                unexpected = t,
                reference = reference,
            )
        }
    }
}

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
