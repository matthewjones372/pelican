package io.github.matthewjones372.pelican.spi

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.ApiErrorEnvelope
import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.BodyDecodeFailure
import io.github.matthewjones372.pelican.DecodeFailure
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.NotAcceptable
import io.github.matthewjones372.pelican.PayloadTooLarge
import io.github.matthewjones372.pelican.Refusal
import io.github.matthewjones372.pelican.RefusalBody
import io.github.matthewjones372.pelican.RefusalReason
import io.github.matthewjones372.pelican.RefusalRenderer
import io.github.matthewjones372.pelican.Unrouted
import io.github.matthewjones372.pelican.UnsupportedMediaType
import io.github.matthewjones372.pelican.unwrapCompletion
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
    is Unrouted -> ApiError(t.status, t.message, t.detail)
    is DecodeFailure -> ApiError(400, "Invalid parameter", t.message)
    is BodyDecodeFailure -> ApiError(400, "Malformed request body", t.message)
    is UnsupportedMediaType -> ApiError(415, t.message, t.detail)
    is NotAcceptable -> ApiError(406, "Not acceptable", t.message)
    is PayloadTooLarge -> ApiError(413, "Payload too large", t.message)
    else -> null
}

/**
 * Which of these was a request refused before the chain, and under what name.
 *
 * Narrower than [described] on purpose, and the narrowing is the whole design
 * of the counter it feeds. An [ApiException] is missing because it is what a
 * filter and a handler throw as well as what an interpreter throws, so counting
 * it would count requests `http.server.requests` has already counted; the 500
 * nobody described is missing for the same reason. Every entry below is a type
 * only Pelican itself raises, and it raises them all before the outermost
 * filter is entered.
 */
private fun reasonOf(t: Throwable): RefusalReason? = when (t) {
    is Unrouted -> RefusalReason.UNMATCHED
    is DecodeFailure, is BodyDecodeFailure -> RefusalReason.DECODE
    is UnsupportedMediaType -> RefusalReason.CONTENT_TYPE
    is NotAcceptable -> RefusalReason.ACCEPT
    is PayloadTooLarge -> RefusalReason.BODY_LIMIT
    else -> null
}

/** What a throwable says about itself, without the body that would go out with it. */
class ClassifiedError(val error: ApiError, val unexpected: Throwable?, val reference: String?)

/**
 * Decides the response for a throwable, and writes it in the dialect [api] was
 * configured with.
 *
 * [api] is the whole settings value rather than the three fields this reads, and
 * it has no default, because a call site that forgot one would answer in an
 * envelope nobody chose — silently, and only for the refusals that reach it.
 * Null is for the caller with no service in scope at all; every interpreter has
 * one.
 *
 * [Api.onRefusal] is told here rather than at the point each refusal is raised,
 * because a raised refusal is not yet one that went out: a filter can catch it,
 * and only this function is on the way to the wire. Nothing else may report a
 * refusal, so a refusal cannot be answered and left uncounted.
 */
fun renderError(raw: Throwable, api: Api?, endpoint: Endpoint<*, *>? = null): RenderedError {
    val t = unwrapCompletion(raw)
    val renderer = api?.refusals ?: ApiErrorEnvelope
    val template = endpoint?.pathSpec?.template
    val classified = classify(t, api)

    val observer = api?.onRefusal
    if (observer != null) reasonOf(t)?.let { observer.refused(it, classified.error.status, template) }

    return RenderedError(
        classified.error,
        // Only an ApiException carries headers of its own; the rest are raised
        // where no response was being described.
        if (t is ApiException) t.headers else emptyList(),
        classified.unexpected,
        classified.reference,
        body = renderer.bodyFor(classified.error.refusal(classified.reference, template)),
    )
}

/**
 * The classification alone, for a caller that answers in an envelope it does not
 * choose.
 *
 * An MCP tool result is JSON-RPC, whose shape the protocol fixes, so neither
 * [Api.refusals] nor [Api.onRefusal] has anything to say about it: the body is
 * not written here, and the call carried no HTTP status that a refusal counter
 * could honestly report.
 */
fun classifyError(raw: Throwable, api: Api?): ClassifiedError = classify(unwrapCompletion(raw), api)

private fun classify(t: Throwable, api: Api?): ClassifiedError {
    val described = described(t)
    if (described != null) return ClassifiedError(described, unexpected = null, reference = null)

    val reference = newReference()
    return ClassifiedError(
        ApiError(
            INTERNAL_SERVER_ERROR,
            "Internal server error",
            if (api?.exposeInternalErrors == true) t.toString() else "Reference: $reference",
        ),
        unexpected = t,
        reference = reference,
    )
}

/** The classification, and only the classification, as a renderer is allowed to see it. */
private fun ApiError.refusal(reference: String?, pathTemplate: String?): Refusal =
    Refusal(status, error, detail, reference, pathTemplate)

/**
 * The bytes under the media type the same renderer publishes, so a response
 * cannot be labelled with one thing and documented as another.
 */
private fun RefusalRenderer.bodyFor(refusal: Refusal): RefusalBody = RefusalBody(mediaType, render(refusal))

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
