package dev.pelican.http4k

import dev.pelican.Api
import dev.pelican.ApiError
import dev.pelican.BodyCodec
import dev.pelican.ByteStreamOutput
import dev.pelican.EmptyOutput
import dev.pelican.Endpoint
import dev.pelican.FallibleOutput
import dev.pelican.JsonArrayOutput
import dev.pelican.JsonOutput
import dev.pelican.NdjsonOutput
import dev.pelican.Outcome
import dev.pelican.Output
import dev.pelican.SseOutput
import dev.pelican.TextOutput
import dev.pelican.renderError
import dev.pelican.successNamedBy
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
 *
 * Note the split of responsibilities. Core knows how to render one element —
 * `NdjsonOutput.frame`, `SseOutput.frame` — and this file knows how to put
 * elements on a socket. A streaming output becomes a body backed by
 * [FrameInputStream], which encodes an element only when the server asks for
 * the bytes, so the sequence is walked at the speed the socket drains.
 *
 * [JsonArrayOutput] is the one output core does not frame; `jsonArrayFrames`
 * supplies the brackets and commas here.
 *
 * [codecs] were resolved for this endpoint when the handler was built, not
 * looked up per request.
 */
@Suppress("UNCHECKED_CAST")
internal fun buildResponse(out: Output<*>, value: Any?, codecs: EndpointCodecs): Response {
    // Every output carrying a payload type had its codec resolved when the
    // handler was built, so a null here is a bug in that resolution rather
    // than anything a request can provoke.
    fun payload(): BodyCodec<Any?> = checkNotNull(codecs.payloadFor(out)) { "No codec was resolved for $out" }

    // The handler named one of the endpoint's declared responses, and that
    // declaration supplies the status, the media type and the type the body is
    // written as — so this is the response the document promised, rather than
    // whichever one happened to be first.
    if (out is FallibleOutput<*, *>) {
        return when (val outcome = value as Outcome<*, *>) {
            is Outcome.Ok<*> -> successResponse(out, outcome, codecs)
            is Outcome.Err<*> -> failureResponse(out, outcome, codecs)
        }
    }

    val response = Response(statusOf(out.status))

    return when (out) {
        // Built in one construction rather than as `Response(...).header(...).body(...)`.
        // An http4k Response is immutable, so each of those steps copies the
        // whole thing: three objects and two header lists where one of each
        // will do. Measured at roughly 300 bytes a request — more than
        // everything else this interpreter allocates put together.
        is JsonOutput<*> -> jsonResponse(out.status, payload().encodeToString(value))

        is TextOutput -> MemoryResponse(statusOf(out.status), TEXT_HEADERS, MemoryBody(value as String))

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
                .streaming(elements(value).map { o.frame(c, it) })
        }

        is JsonArrayOutput<*> ->
            response
                .header(CONTENT_TYPE, "application/json")
                .streaming(jsonArrayFrames(elements(value), payload()))

        // Handed over as it stands: whatever the handler opened is copied to
        // the socket and closed by the server once written.
        is ByteStreamOutput ->
            response
                .header(CONTENT_TYPE, out.mediaType)
                .body(value as InputStream, null)

        // Unreachable: handled above, before any payload is touched.
        is FallibleOutput<*, *> -> error("Unreachable")
    }
}

/**
 * A body with no declared length, which is what tells a backend to chunk it.
 * Passing a length here would make the server buffer the whole stream to find
 * out what to declare.
 */
private fun Response.streaming(frames: Sequence<String>): Response =
    body(FrameInputStream(frames), null)

/**
 * Renders whichever declared success the handler named.
 *
 * Which one that is, and whether it is carrying what it promised, is core's
 * answer rather than this file's — `successNamedBy` — because a bare
 * `ok(value)` names none and so carries no headers, and three interpreters
 * deciding separately what that means is three chances to send a response the
 * document does not describe.
 */
private fun successResponse(
    out: FallibleOutput<*, *>,
    ok: Outcome.Ok<*>,
    codecs: EndpointCodecs,
): Response {
    val chosen = out.successNamedBy(ok)
    val response = buildResponse(chosen, ok.value, codecs)
    // Encoded and checked against the declaration when the handler produced
    // the response, so there is nothing left to decide here.
    return ok.headers.fold(response) { res, (name, value) -> res.header(name, value) }
}

/**
 * Renders one declared failure.
 *
 * The status comes from the declaration the handler named rather than from the
 * payload's type, so two failures carrying the same type under different
 * statuses stay distinct.
 */
private fun failureResponse(
    out: FallibleOutput<*, *>,
    err: Outcome.Err<*>,
    codecs: EndpointCodecs,
): Response {
    val declared = err.declared
    check(out.failures.any { it === declared }) {
        "$declared was returned by a handler but $out never declared it"
    }
    val cls = declared.type.classifier as? KClass<*>
    check(cls == null || cls.isInstance(err.error)) {
        "$declared carries ${declared.type} but the handler returned ${err.error?.let { it::class }}"
    }
    val codec = checkNotNull(codecs.alternatives[declared]) { "No codec was resolved for $declared" }
    // Encoded and checked against the declaration when the handler produced
    // the failure, so there is nothing left to decide here.
    return jsonResponse(declared.status, codec.encodeToString(err.error), err.headers)
}

/** One JSON response, one allocation of each thing it is made of. */
private fun jsonResponse(status: Int, body: String, extra: List<Pair<String, String>> = emptyList()): Response =
    MemoryResponse(
        statusOf(status),
        if (extra.isEmpty()) JSON_HEADERS else JSON_HEADERS + extra,
        MemoryBody(body),
    )

// The content type of a JSON or text response is the same list every time.
// Built once rather than per request: the list and the pair inside it are two
// allocations that never differ.
private val JSON_HEADERS = listOf(CONTENT_TYPE to "application/json")
private val TEXT_HEADERS = listOf(CONTENT_TYPE to "text/plain; charset=utf-8")

@Suppress("UNCHECKED_CAST")
private fun elements(value: Any?): Sequence<Any?> = value as Sequence<Any?>

/** http4k's own status where it knows one, so the reason phrase is the standard text. */
private fun statusOf(code: Int): Status = Status.fromCode(code) ?: Status(code, null)

private val log: Logger = LoggerFactory.getLogger("dev.pelican.http4k")

/**
 * Errors are rendered by hand, through core's own JSON tree, rather than
 * through the configured codec. A codec that has just failed is not the thing
 * to reach for when reporting that it failed.
 *
 * Which throwable becomes which response is core's decision ([renderError]), so
 * the three backends cannot drift. What is local to each is the logging: an
 * unexpected throwable is logged *here*, against the reference the caller was
 * given, because Pelican catches it and the server underneath would otherwise
 * never see it.
 */
internal fun errorResponse(raw: Throwable, api: Api?, endpoint: Endpoint<*, *>? = null): Response {
    val rendered = renderError(raw, api?.exposeInternalErrors ?: false)

    rendered.unexpected?.let { failure ->
        // An unexpected failure always carries a reference; that is what
        // renderError produced it for.
        val reference = checkNotNull(rendered.reference) { "an unexpected failure with no reference" }
        val hook = api?.onServerError
        if (hook != null) hook(reference, endpoint, failure)
        else log.error("Unhandled failure in {} [ref {}]", endpoint ?: "?", rendered.reference, failure)
    }

    val error = rendered.error
    val headers = if (rendered.headers.isEmpty()) JSON_HEADERS else JSON_HEADERS + rendered.headers
    return MemoryResponse(statusOf(error.status), headers, MemoryBody(error.toJson().render()))
}
