package io.github.matthewjones372.pelican.spi

import io.github.matthewjones372.pelican.*

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
    /**
     * The body, already written by the [Api]'s [RefusalRenderer]. An interpreter
     * that encoded [error] itself would be a fourth dialect nobody configured,
     * so the bytes come from here and the status from [error].
     */
    val body: RefusalBody,
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
 * Decides the response for a throwable, and writes it in the dialect [api] was
 * configured with.
 *
 * [api] is the whole settings value rather than the two fields this reads, and
 * it has no default, because a call site that forgot one would answer in an
 * envelope nobody chose — silently, and only for the refusals that reach it.
 * Null is for the caller with no service in scope at all; every interpreter has
 * one.
 */
fun renderError(raw: Throwable, api: Api?, endpoint: Endpoint<*, *>? = null): RenderedError {
    val t = unwrapCompletion(raw)
    val renderer = api?.refusals ?: ApiErrorEnvelope
    val template = endpoint?.pathSpec?.template

    val described = described(t)
    if (described != null) {
        return RenderedError(
            described,
            // Only an ApiException carries headers of its own; the rest are
            // raised where no response was being described.
            if (t is ApiException) t.headers else emptyList(),
            unexpected = null,
            reference = null,
            body = renderer.render(described.refusal(reference = null, pathTemplate = template)),
        )
    }

    val reference = newReference()
    val error = ApiError(
        INTERNAL_SERVER_ERROR,
        "Internal server error",
        if (api?.exposeInternalErrors == true) t.toString() else "Reference: $reference",
    )
    return RenderedError(
        error,
        emptyList(),
        unexpected = t,
        reference = reference,
        body = renderer.render(error.refusal(reference, template)),
    )
}

/** The classification, and only the classification, as a renderer is allowed to see it. */
private fun ApiError.refusal(reference: String?, pathTemplate: String?): Refusal =
    Refusal(status, error, detail, reference, pathTemplate)

/**
 * The status a throwable becomes, without building the body that would go with
 * it. What a filter wants when it is measuring rather than answering — see
 * [Endpoint.statusFor], which is the only caller that should need this.
 */
internal fun statusOfError(raw: Throwable): Int =
    described(unwrapCompletion(raw))?.status ?: INTERNAL_SERVER_ERROR

/** What anything nobody described becomes. */
private const val INTERNAL_SERVER_ERROR = 500

/**
 * Short, unique enough to find in a log, and not sequential — a counter would
 * tell a caller how much traffic the service takes.
 */
private fun newReference(): String =
    java.util.UUID.randomUUID().toString().replace("-", "").take(REFERENCE_LENGTH)

/** Long enough to be unique in a log, short enough to read out over a phone. */
private const val REFERENCE_LENGTH = 12
