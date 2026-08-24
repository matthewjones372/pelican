package dev.pelican.ktor

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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletionException
import kotlin.reflect.KClass

/**
 * Turns a described [Output] plus a handler's result into a Ktor response.
 *
 * Note the split of responsibilities. Core knows how to render one element —
 * `NdjsonOutput.frame`, `SseOutput.frame` — and this file knows how to put
 * elements on a socket. A streaming output is written with
 * `respondBytesWriter`, collecting the handler's flow and flushing each frame
 * as it is encoded, so the flow is walked at the speed the socket drains and
 * nothing is assembled first.
 *
 * The flush matters: without it the engine holds frames until its own buffer
 * fills, which turns a stream into a slow list. This is the one part of
 * streaming the other backends could not fully decide for themselves — see the
 * note on `start` — and on Ktor it is decided here, for every engine.
 *
 * [JsonArrayOutput] is the one output core does not frame; [jsonArrayFrames]
 * supplies the brackets and commas here.
 *
 * [codecs] were resolved for this endpoint when the routes were built, not
 * looked up per request.
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun respond(
    call: ApplicationCall,
    out: Output<*>,
    value: Any?,
    codecs: EndpointCodecs,
) {
    // Every output carrying a payload type had its codec resolved when the
    // routes were built, so a null here is a bug in that resolution rather
    // than anything a request can provoke.
    fun payload(): BodyCodec<Any?> = checkNotNull(codecs.payloadFor(out)) { "No codec was resolved for $out" }

    // The handler named one of the endpoint's declared responses, and that
    // declaration supplies the status, the media type and the type the body is
    // written as — so this is the response the document promised, rather than
    // whichever one happened to be first.
    if (out is FallibleOutput<*, *>) {
        return when (val outcome = value as Outcome<*, *>) {
            is Outcome.Ok<*> -> respondSuccess(call, out, outcome, codecs)
            is Outcome.Err<*> -> respondFailure(call, out, outcome, codecs)
        }
    }

    val status = statusOf(out.status)

    when (out) {
        is JsonOutput<*> ->
            call.respondText(payload().encodeToString(value), ContentType.Application.Json, status)

        is TextOutput ->
            call.respondText(value as String, ContentType.Text.Plain, status)

        is EmptyOutput -> call.respond(status)

        is NdjsonOutput<*> -> {
            val o = out as NdjsonOutput<Any?>
            val c = payload()
            call.stream(ContentType("application", "x-ndjson"), status, elements(value).map { o.frame(c, it) })
        }

        is SseOutput<*> -> {
            val o = out as SseOutput<Any?>
            val c = payload()
            call.stream(ContentType.Text.EventStream, status, elements(value).map { o.frame(c, it) })
        }

        is JsonArrayOutput<*> ->
            call.stream(ContentType.Application.Json, status, jsonArrayFrames(elements(value), payload()))

        // Handed over as it stands: whatever the handler opened is copied to
        // the socket as it produces bytes, with no buffer in between.
        is ByteStreamOutput -> call.respondBytesWriter(ContentType.parse(out.mediaType), status) {
            (value as ByteReadChannel).copyTo(this)
        }

        // Unreachable: handled above, before any payload is touched.
        is FallibleOutput<*, *> -> error("Unreachable")
    }
}

/**
 * A body with no declared length, which is what tells Ktor to chunk it, and one
 * flush per frame, which is what makes those chunks leave promptly. The frames
 * are encoded inside the collect, so nothing is rendered before the socket asks
 * for it.
 */
private suspend fun ApplicationCall.stream(
    contentType: ContentType,
    status: HttpStatusCode,
    frames: Flow<String>,
) = respondBytesWriter(contentType, status) {
    frames.collect { frame ->
        writeStringUtf8(frame)
        flush()
    }
}

/**
 * Frames a stream of documents as one JSON array.
 *
 * Unlike NDJSON and SSE, core does not frame this one — the separators are the
 * backend's business, because Pekko already has `EntityStreamingSupport.json()`
 * and reimplementing it there would be worse. Ktor has no equivalent, so the
 * commas are put in here: the opening bracket travels with the first element
 * rather than ahead of it, so an empty stream still renders `[]` and a failure
 * to produce the first element has not yet committed to an array.
 */
internal fun jsonArrayFrames(elements: Flow<Any?>, codec: BodyCodec<Any?>): Flow<String> =
    flow {
        var seen = false
        elements.collect { element ->
            emit((if (seen) "," else "[") + codec.encodeToString(element))
            seen = true
        }
        emit(if (seen) "]" else "[]")
    }

/**
 * Renders whichever declared success the handler named.
 *
 * Which one that is, and whether it is carrying what it promised, is core's
 * answer rather than this file's — `successNamedBy` — because a bare
 * `ok(value)` names none and so carries no headers, and three interpreters
 * deciding separately what that means is three chances to send a response the
 * document does not describe.
 */
private suspend fun respondSuccess(
    call: ApplicationCall,
    out: FallibleOutput<*, *>,
    ok: Outcome.Ok<*>,
    codecs: EndpointCodecs,
) {
    val chosen = out.successNamedBy(ok)
    // Encoded and checked against the declaration when the handler produced
    // the response, so there is nothing left to decide here. Appended before
    // the body, because writing the body is what commits the response.
    ok.headers.forEach { (name, value) -> call.response.headers.append(name, value) }
    respond(call, chosen, ok.value, codecs)
}

/**
 * Renders one declared failure.
 *
 * The status comes from the declaration the handler named rather than from the
 * payload's type, so two failures carrying the same type under different
 * statuses stay distinct.
 */
private suspend fun respondFailure(
    call: ApplicationCall,
    out: FallibleOutput<*, *>,
    err: Outcome.Err<*>,
    codecs: EndpointCodecs,
) {
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
    // the failure, so there is nothing left to decide here. Appended before
    // the body, because writing the body is what commits the response.
    err.headers.forEach { (name, value) -> call.response.headers.append(name, value) }
    call.respondText(
        codec.encodeToString(err.error),
        ContentType.Application.Json,
        statusOf(declared.status),
    )
}

@Suppress("UNCHECKED_CAST")
private fun elements(value: Any?): Flow<Any?> = value as Flow<Any?>

/** Ktor's own status where it knows one, so the reason phrase is the standard text. */
private fun statusOf(code: Int): HttpStatusCode = HttpStatusCode.fromValue(code)

private val log: Logger = LoggerFactory.getLogger("dev.pelican.ktor")

/**
 * Errors are rendered by hand, through core's own JSON tree, rather than
 * through the configured codec. A codec that has just failed is not the thing
 * to reach for when reporting that it failed.
 *
 * Which throwable becomes which response is core's decision ([renderError]), so
 * the three backends cannot drift. What is local to each is the logging: an
 * unexpected throwable is logged *here*, against the reference the caller was
 * given, because Pelican catches it and Ktor would otherwise never see it.
 */
internal suspend fun ApplicationCall.respondError(raw: Throwable, api: Api?, endpoint: Endpoint<*, *>? = null) {
    val rendered = renderError(raw, api?.exposeInternalErrors ?: false)

    rendered.unexpected?.let { failure ->
        val hook = api?.onServerError
        if (hook != null) hook(checkNotNull(rendered.reference), endpoint, failure)
        else log.error("Unhandled failure in {} [ref {}]", endpoint ?: "?", rendered.reference, failure)
    }

    rendered.headers.forEach { (name, value) -> response.headers.append(name, value) }
    val error = rendered.error
    respondText(error.toJson().render(), ContentType.Application.Json, statusOf(error.status))
}
