package io.github.matthewjones372.pelican.ktor

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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletionException
import kotlin.reflect.KClass
import kotlin.time.Duration

/**
 * Turns a described [Output] plus a handler's result into a Ktor response.
 */
@Suppress("UNCHECKED_CAST")
internal suspend fun respond(
    call: ApplicationCall,
    out: Output<*>,
    value: Any?,
    codecs: EndpointCodecs,
    accept: List<String>,
) {
    // Resolved at route-build time, so a null is a bug in that resolution
    // rather than anything a request can provoke.
    fun payload(): BodyCodec<Any?> = checkNotNull(codecs.payloadFor(out)) { "No codec was resolved for $out" }

    // The declaration the handler named supplies the status, the media type
    // and the type the body is written as.
    if (out is FallibleOutput<*, *>) {
        return when (val outcome = value as Outcome<*, *>) {
            is Outcome.Ok<*> -> respondSuccess(call, out, outcome, codecs, accept)
            is Outcome.Err<*> -> respondFailure(call, out, outcome, codecs)
        }
    }

    // Which rendering of one value goes out is core's answer, from the same
    // scoring the 406 above was decided with.
    if (out is NegotiatedOutput<*>) {
        return respond(call, out.selectedFor(accept), value, codecs, accept)
    }

    val status = statusOf(out.status)

    when (out) {
        is JsonOutput<*> ->
            call.respondText(payload().encodeToString(value), ContentType.Application.Json, status)

        is TextOutput ->
            call.respondText(value as String, ContentType.Text.Plain, status)

        is MediaOutput<*> ->
            call.respondText(payload().encodeToString(value), ContentType.parse(out.mediaType), status)

        is EmptyOutput -> call.respond(status)

        is NdjsonOutput<*> -> {
            val o = out as NdjsonOutput<Any?>
            val c = payload()
            call.stream(ContentType("application", "x-ndjson"), status, elements(value).map { o.frame(c, it) })
        }

        is SseOutput<*> -> {
            val o = out as SseOutput<Any?>
            val c = payload()
            val frames = elements(value).map { o.frame(c, it) }
            call.stream(ContentType.Text.EventStream, status, frames.after(o.prelude()).withKeepAlive(o.keepAlive))
        }

        is JsonArrayOutput<*> ->
            call.stream(ContentType.Application.Json, status, jsonArrayFrames(elements(value), payload()))

        // Handed over as it stands: whatever the handler opened is copied to
        // the socket as it produces bytes, with no buffer in between.
        is ByteStreamOutput -> call.respondBytesWriter(ContentType.parse(out.mediaType), status) {
            (value as ByteReadChannel).copyTo(this)
        }

        // Unreachable: both are handled above, before any payload is touched.
        is FallibleOutput<*, *>, is NegotiatedOutput<*> -> error("Unreachable")
    }
}

/**
 * No declared length, which is what tells Ktor to chunk it, and one flush per
 * frame. The frames are encoded inside the collect, so nothing is rendered
 * before the socket asks for it.
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

/** Puts an SSE stream's opening directive in front of its first event. */
private fun Flow<String>.after(prelude: String?): Flow<String> =
    if (prelude == null) this else flow {
        emit(prelude)
        emitAll(this@after)
    }

/**
 * Injects an SSE comment down a stream that has gone quiet. Idle rather than
 * periodic, matching `Source.keepAlive` — a busy stream sends nothing extra.
 */
internal fun Flow<String>.withKeepAlive(interval: Duration?): Flow<String> {
    if (interval == null) return this
    return channelFlow {
        val upstream = Channel<String>(Channel.RENDEZVOUS)
        val pump = launch {
            try {
                this@withKeepAlive.collect { upstream.send(it) }
                upstream.close()
            } catch (t: Throwable) {
                // Closed with the cause rather than rethrown, so a failed
                // stream has one path out instead of two racing ones.
                upstream.close(t)
            }
        }
        try {
            while (true) {
                val next = withTimeoutOrNull(interval) { upstream.receiveCatching() }
                when {
                    next == null -> send(SseOutput.KEEP_ALIVE_FRAME)

                    next.isClosed -> {
                        next.exceptionOrNull()?.let { throw it }
                        break
                    }

                    else -> send(next.getOrThrow())
                }
            }
        } finally {
            pump.cancel()
        }
    }
}

/**
 * Frames a stream of documents as one JSON array, which core leaves to the
 * backend. The opening bracket travels with the first element rather than
 * ahead of it, so an empty stream renders `[]` and a first-element failure has
 * not yet committed to an array.
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
 * Renders whichever declared success the handler named. Which one, and whether
 * it carries what it promised, is `successNamedBy`'s answer — a bare
 * `ok(value)` names none, and three interpreters deciding that separately is
 * three chances to send an undescribed response.
 */
private suspend fun respondSuccess(
    call: ApplicationCall,
    out: FallibleOutput<*, *>,
    ok: Outcome.Ok<*>,
    codecs: EndpointCodecs,
    accept: List<String>,
) {
    val chosen = out.successNamedBy(ok)
    // Encoded and checked when the handler produced the response. Appended
    // before the body, because writing the body commits the response.
    ok.headers.forEach { (name, value) -> call.response.headers.append(name, value) }
    respond(call, chosen, ok.value, codecs, accept)
}

/**
 * Renders one declared failure. The status comes from the declaration rather
 * than the payload's type, so two failures sharing a type stay distinct.
 */
private suspend fun respondFailure(
    call: ApplicationCall,
    out: FallibleOutput<*, *>,
    err: Outcome.Err<*>,
    codecs: EndpointCodecs,
) {
    val declared = out.failureNamedBy(err)
    val codec = checkNotNull(codecs.alternatives[declared]) { "No codec was resolved for $declared" }
    // Encoded and checked when the handler produced the failure. Appended
    // before the body, because writing the body commits the response.
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

private val log: Logger = LoggerFactory.getLogger("io.github.matthewjones372.pelican.ktor")

/**
 * Rendered through core's own tree rather than the configured codec: a codec
 * that has just failed is not the thing to report that it failed.
 *
 * [renderError] decides which throwable becomes which response and writes the
 * body in the dialect the service configured. What is local is the logging —
 * Pelican catches the throwable, so Ktor never sees it.
 *
 * [api] is not nullable, so a preflight refused before any route matched is
 * still written in that dialect. That site passed null once.
 */
internal suspend fun ApplicationCall.respondError(raw: Throwable, api: Api, endpoint: Endpoint<*, *>? = null) {
    val rendered = renderError(raw, api, endpoint)

    rendered.unexpected?.let { failure ->
        val hook = api.onServerError
        if (hook != null) hook(checkNotNull(rendered.reference), endpoint, failure)
        else log.error("Unhandled failure in {} [ref {}]", endpoint ?: "?", rendered.reference, failure)
    }

    rendered.headers.forEach { (name, value) -> response.headers.append(name, value) }
    respondBytes(
        rendered.body.bytes,
        ContentType.parse(rendered.body.mediaType),
        statusOf(rendered.error.status),
    )
}
