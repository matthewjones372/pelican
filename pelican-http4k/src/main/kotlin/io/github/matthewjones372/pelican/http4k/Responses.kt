package io.github.matthewjones372.pelican.http4k

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.ByteStreamOutput
import io.github.matthewjones372.pelican.EmptyOutput
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.FallibleOutput
import io.github.matthewjones372.pelican.JsonArrayOutput
import io.github.matthewjones372.pelican.JsonOutput
import io.github.matthewjones372.pelican.MediaOutput
import io.github.matthewjones372.pelican.NdjsonOutput
import io.github.matthewjones372.pelican.NegotiatedOutput
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.Output
import io.github.matthewjones372.pelican.SseOutput
import io.github.matthewjones372.pelican.TextOutput
import io.github.matthewjones372.pelican.spi.failureNamedBy
import io.github.matthewjones372.pelican.spi.renderError
import io.github.matthewjones372.pelican.spi.selectedFor
import io.github.matthewjones372.pelican.spi.successNamedBy
import org.http4k.core.MemoryBody
import org.http4k.core.MemoryResponse
import org.http4k.core.Response
import org.http4k.core.Status
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.InputStream
import kotlin.reflect.KClass

internal const val CONTENT_TYPE = "Content-Type"

/**
 * Turns a described [Output] plus a handler's result into an http4k response.
 */
@Suppress("UNCHECKED_CAST")
internal fun buildResponse(
    out: Output<*>,
    value: Any?,
    codecs: EndpointCodecs,
    accept: List<String>,
): Response {
    // Resolved when the handler was built, so a null is a bug in that
    // resolution rather than anything a request can provoke.
    fun payload(): BodyCodec<Any?> = checkNotNull(codecs.payloadFor(out)) { "No codec was resolved for $out" }

    // The declaration the handler named supplies the status, the media type
    // and the type the body is written as.
    if (out is FallibleOutput<*, *>) {
        return when (val outcome = value as Outcome<*, *>) {
            is Outcome.Ok<*> -> successResponse(out, outcome, codecs, accept)
            is Outcome.Err<*> -> failureResponse(out, outcome, codecs)
        }
    }

    // Which rendering of one value goes out is core's answer, from the same
    // scoring the 406 above was decided with.
    if (out is NegotiatedOutput<*>) {
        return buildResponse(out.selectedFor(accept), value, codecs, accept)
    }

    val response = Response(statusOf(out.status))

    return when (out) {
        // One construction rather than `Response(...).header(...).body(...)`:
        // an http4k Response is immutable, so each step copies the whole
        // thing. Measured at roughly 300 bytes a request.
        is JsonOutput<*> -> jsonResponse(out.status, payload().encodeToString(value))

        is TextOutput -> MemoryResponse(statusOf(out.status), TEXT_HEADERS, MemoryBody(value as String))

        is MediaOutput<*> -> MemoryResponse(
            statusOf(out.status),
            listOf(CONTENT_TYPE to out.mediaType),
            MemoryBody(payload().encodeToString(value)),
        )

        is EmptyOutput -> response

        is NdjsonOutput<*> -> {
            val o = out as NdjsonOutput<Any?>
            val c = payload()
            response
                .header(CONTENT_TYPE, "application/x-ndjson")
                .streaming(elements(value).map { o.frame(c, it) })
        }

        is SseOutput<*> -> {
            val o = out as SseOutput<Any?>
            val c = payload()
            response
                .header(CONTENT_TYPE, "text/event-stream")
                .streaming(elements(value).map { o.frame(c, it) }.after(o.prelude()).withKeepAlive(o.keepAlive))
        }

        is JsonArrayOutput<*> ->
            response
                .header(CONTENT_TYPE, "application/json")
                .streaming(jsonArrayFrames(elements(value), payload()))

        // Copied to the socket as it stands, and closed by the server.
        is ByteStreamOutput ->
            response
                .header(CONTENT_TYPE, out.mediaType)
                .body(value as InputStream, null)

        // Unreachable: both are handled above, before any payload is touched.
        is FallibleOutput<*, *>, is NegotiatedOutput<*> -> error("Unreachable")
    }
}

/**
 * No declared length, which is what tells a backend to chunk it. Passing one
 * would make the server buffer the whole stream to find out what to declare.
 */
private fun Response.streaming(frames: Sequence<String>): Response =
    body(FrameInputStream(frames), null)

/**
 * Renders whichever declared success the handler named. Which one, and whether
 * it carries what it promised, is `successNamedBy`'s answer — a bare
 * `ok(value)` names none, and three interpreters deciding that separately is
 * three chances to send an undescribed response.
 */
private fun successResponse(
    out: FallibleOutput<*, *>,
    ok: Outcome.Ok<*>,
    codecs: EndpointCodecs,
    accept: List<String>,
): Response {
    val chosen = out.successNamedBy(ok)
    val response = buildResponse(chosen, ok.value, codecs, accept)
    // Encoded and checked when the handler produced the response.
    return ok.headers.fold(response) { res, (name, value) -> res.header(name, value) }
}

/**
 * Renders one declared failure. The status comes from the declaration rather
 * than the payload's type, so two failures sharing a type stay distinct.
 */
private fun failureResponse(
    out: FallibleOutput<*, *>,
    err: Outcome.Err<*>,
    codecs: EndpointCodecs,
): Response {
    val declared = out.failureNamedBy(err)
    val codec = checkNotNull(codecs.alternatives[declared]) { "No codec was resolved for $declared" }
    // Encoded and checked when the handler produced the failure.
    return jsonResponse(declared.status, codec.encodeToString(err.error), err.headers)
}

/** One JSON response, one allocation of each thing it is made of. */
private fun jsonResponse(status: Int, body: String, extra: List<Pair<String, String>> = emptyList()): Response =
    MemoryResponse(
        statusOf(status),
        if (extra.isEmpty()) JSON_HEADERS else JSON_HEADERS + extra,
        MemoryBody(body),
    )

// Built once rather than per request: the list and the pair inside it are two
// allocations that never differ.
private val JSON_HEADERS = listOf(CONTENT_TYPE to "application/json")
private val TEXT_HEADERS = listOf(CONTENT_TYPE to "text/plain; charset=utf-8")

@Suppress("UNCHECKED_CAST")
private fun elements(value: Any?): Sequence<Any?> = value as Sequence<Any?>

/** http4k's own status where it knows one, so the reason phrase is the standard text. */
private fun statusOf(code: Int): Status = Status.fromCode(code) ?: Status(code, null)

private val log: Logger = LoggerFactory.getLogger("io.github.matthewjones372.pelican.http4k")

/**
 * Rendered through core's own JSON tree rather than the configured codec: a
 * codec that has just failed is not the thing to report that it failed.
 */
internal fun errorResponse(raw: Throwable, api: Api?, endpoint: Endpoint<*, *>? = null): Response {
    val rendered = renderError(raw, api?.exposeInternalErrors ?: false)

    rendered.unexpected?.let { failure ->
        // Always present: renderError produces one for exactly this.
        val reference = checkNotNull(rendered.reference) { "an unexpected failure with no reference" }
        val hook = api?.onServerError
        if (hook != null) hook(reference, endpoint, failure)
        else log.error("Unhandled failure in {} [ref {}]", endpoint ?: "?", rendered.reference, failure)
    }

    val error = rendered.error
    val headers = if (rendered.headers.isEmpty()) JSON_HEADERS else JSON_HEADERS + rendered.headers
    return MemoryResponse(statusOf(error.status), headers, MemoryBody(error.toJson().render()))
}
