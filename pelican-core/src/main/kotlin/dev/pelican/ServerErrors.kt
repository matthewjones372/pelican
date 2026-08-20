package dev.pelican

import java.util.concurrent.CompletionException

/**
 * What one throwable should become on the wire, decided in core so the three
 * backends cannot drift apart about it.
 *
 * The distinction that matters is [unexpected]. A [DecodeFailure] is the
 * caller's mistake and its message is written for them; anything that escapes
 * a handler is *ours*, and its message was written for a log — it may name a
 * table, a host, a file, or a query. So the two are rendered differently:
 *
 *  - expected failures carry their own detail out to the caller, as before;
 *  - an unexpected one carries only a [reference], and the throwable is handed
 *    back for the interpreter to log against that same reference.
 *
 * Which means a 500 now leaves a trace on the server and nothing useful for
 * anyone reading the response. Before this, it was exactly the other way round.
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
 * Decides the response for a throwable.
 *
 * [exposeInternalDetail] puts an unexpected throwable's own message back in the
 * body. For a local run or a test fixture; leaving it on in production is how
 * a stack trace ends up in someone else's browser.
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
    if (t is CompletionException && t.cause != null) unwrapCompletion(t.cause!!) else t

/**
 * Short, unique enough to find in a log, and not sequential — a counter would
 * tell a caller how much traffic the service takes.
 */
private fun newReference(): String =
    java.util.UUID.randomUUID().toString().replace("-", "").take(12)
