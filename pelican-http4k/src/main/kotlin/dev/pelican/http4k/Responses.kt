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
    fun payload(): BodyCodec<Any?> = checkNotNull(codecs.payload) { "No codec was resolved for $out" }

    // A declared failure is rendered by the configured codec, as its own
    // declared type — this is the response the document promised, rather than
    // the framework's generic ApiError.
    if (out is FallibleOutput<*, *>) {
        return when (val outcome = value as Outcome<*, *>) {
            is Outcome.Ok<*> -> buildResponse(out.success, outcome.value, codecs)
            is Outcome.Err<*> -> failureResponse(out, outcome, codecs)
        }
    }

    val response = Response(statusOf(out.status))

    return when (out) {
        is JsonOutput<*> ->
            response
                .header(CONTENT_TYPE, "application/json")
                .body(payload().encodeToString(value))

        is TextOutput ->
            response
                .header(CONTENT_TYPE, "text/plain; charset=utf-8")
                .body(value as String)

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
    val codec = checkNotNull(codecs.failures[declared]) { "No codec was resolved for $declared" }
    return Response(statusOf(declared.status))
        .header(CONTENT_TYPE, "application/json")
        .body(codec.encodeToString(err.error))
}

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
        val hook = api?.onServerError
        if (hook != null) hook(rendered.reference!!, endpoint, failure)
        else log.error("Unhandled failure in {} [ref {}]", endpoint ?: "?", rendered.reference, failure)
    }

    val error = rendered.error
    return rendered.headers
        .fold(
            Response(statusOf(error.status))
                .header(CONTENT_TYPE, "application/json")
                .body(error.toJson().render()),
        ) { res, (name, value) -> res.header(name, value) }
}
