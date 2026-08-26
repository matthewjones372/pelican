package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.spi.*
import org.apache.pekko.NotUsed
import org.apache.pekko.http.javadsl.common.EntityStreamingSupport
import org.apache.pekko.http.javadsl.model.*
import org.apache.pekko.http.javadsl.model.headers.RawHeader
import org.apache.pekko.japi.function.Creator
import org.apache.pekko.stream.javadsl.Source
import org.apache.pekko.util.ByteString
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.toJavaDuration

internal val NDJSON: ContentType.NonBinary =
    MediaTypes.customWithFixedCharset("application", "x-ndjson", HttpCharsets.UTF_8, HashMap(), false)
        .toContentType()

internal val EVENT_STREAM: ContentType.NonBinary = MediaTypes.TEXT_EVENT_STREAM.toContentType()

/**
 * Turns a described [Output] plus a handler's result into a Pekko response.
 *
 * Core renders one element — `NdjsonOutput.frame`, `SseOutput.frame` — and this
 * puts elements on a socket, mapping the source into a chunked entity so
 * back-pressure reaches the source. [JsonArrayOutput] is the exception: Pekko
 * frames a stream of JSON documents as an array already.
 */
@Suppress("UNCHECKED_CAST")
internal fun buildResponse(
    out: Output<*>,
    value: Any?,
    codecs: EndpointCodecs,
    accept: List<String>,
): HttpResponse {
    // Resolved at route-build time, so a null is a bug in that resolution
    // rather than anything a request can provoke.
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

    val entity: ResponseEntity = when (out) {
        is JsonOutput<*> -> HttpEntities.create(
            ContentTypes.APPLICATION_JSON,
            payload().encodeToString(value),
        )

        is TextOutput -> HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, value as String)

        is MediaOutput<*> -> HttpEntities.create(
            contentTypeOf(out.mediaType),
            ByteString.fromString(payload().encodeToString(value)),
        )

        is EmptyOutput -> HttpEntities.EMPTY

        is NdjsonOutput<*> -> {
            val o = out as NdjsonOutput<Any?>
            val c = payload()
            HttpEntities.createChunked(NDJSON, elements(value).map { ByteString.fromString(o.frame(c, it)) })
        }

        is SseOutput<*> -> {
            val o = out as SseOutput<Any?>
            val c = payload()
            val frames = elements(value).map { ByteString.fromString(o.frame(c, it)) }
            HttpEntities.createChunked(EVENT_STREAM, frames.withKeepAlive(o.keepAlive))
        }

        is JsonArrayOutput<*> -> {
            val c = payload()
            val support = EntityStreamingSupport.json()
            val bytes = elements(value)
                .map { ByteString.fromString(c.encodeToString(it)) }
                .via(support.framingRenderer())
            HttpEntities.createChunked(ContentTypes.APPLICATION_JSON, bytes)
        }

        is ByteStreamOutput -> HttpEntities.createChunked(
            contentTypeOf(out.mediaType),
            value as Source<ByteString, NotUsed>,
        )

        // Unreachable: both are handled above, before any payload is touched.
        is FallibleOutput<*, *>, is NegotiatedOutput<*> -> error("Unreachable")
    }

    return HttpResponse.create().withStatus(statusOf(out.status)).withEntity(entity)
}

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
): HttpResponse {
    val chosen = out.successNamedBy(ok)
    val response = buildResponse(chosen, ok.value, codecs, accept)
    // Encoded and checked against the declaration when the handler produced
    // the response, so there is nothing left to decide here.
    return if (ok.headers.isEmpty()) response
    else response.addHeaders(ok.headers.map { (name, value) -> RawHeader.create(name, value) })
}

/**
 * Renders one declared failure. The status comes from the declaration rather
 * than the payload's type, so two failures sharing a type stay distinct.
 */
private fun failureResponse(
    out: FallibleOutput<*, *>,
    err: Outcome.Err<*>,
    codecs: EndpointCodecs,
): HttpResponse {
    val declared = out.failureNamedBy(err)
    val codec = checkNotNull(codecs.alternatives[declared]) { "No codec was resolved for $declared" }
    return HttpResponse.create()
        .withStatus(statusOf(declared.status))
        .withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, codec.encodeToString(err.error)))
        // Encoded and checked against the declaration when the handler
        // produced the failure, so there is nothing left to decide here.
        .addHeaders(err.headers.map { (name, value) -> RawHeader.create(name, value) })
}

@Suppress("UNCHECKED_CAST")
private fun elements(value: Any?): Source<Any?, NotUsed> = value as Source<Any?, NotUsed>

/**
 * Pekko's own status where it knows one, a custom one where it does not.
 * `StatusCodes.get` is `int2StatusCode`, which throws for anything unregistered
 * — so a legal 419 used to document a 419 and answer 500. The range is checked
 * in core, so nothing outside 100..599 reaches this.
 */
private fun statusOf(code: Int): StatusCode =
    StatusCodes.lookup(code).orElseGet { StatusCodes.custom(code, "", "") }

/**
 * Injects an SSE comment down a stream that has gone quiet. `Source.keepAlive`
 * fires on idle rather than on a timer, so a busy stream sends nothing extra.
 */
private fun Source<ByteString, NotUsed>.withKeepAlive(interval: Duration?): Source<ByteString, NotUsed> =
    if (interval == null) this
    else keepAlive(interval.toJavaDuration(), Creator { ByteString.fromString(SseOutput.KEEP_ALIVE_FRAME) })

/**
 * Built once per declared media type. Cannot grow without bound: the keys come
 * from endpoint descriptions, never from a request.
 */
private val contentTypes = ConcurrentHashMap<String, ContentType>()

private fun contentTypeOf(mediaType: String): ContentType =
    contentTypes.computeIfAbsent(mediaType, ::parseContentType)

private fun parseContentType(mediaType: String): ContentType {
    val slash = mediaType.indexOf('/')
    require(slash > 0) { "Not a media type: $mediaType" }
    val main = mediaType.substring(0, slash)
    val sub = mediaType.substring(slash + 1).substringBefore(';').trim()
    return when {
        main == "application" && sub == "octet-stream" -> ContentTypes.APPLICATION_OCTET_STREAM

        main == "application" && sub == "json" -> ContentTypes.APPLICATION_JSON

        main == "text" -> MediaTypes.customWithFixedCharset(main, sub, HttpCharsets.UTF_8, HashMap(), false)
            .toContentType()

        else -> ContentTypes.create(MediaTypes.customBinary(main, sub, /* compressible = */ true))
    }
}

private val log: Logger = LoggerFactory.getLogger("io.github.matthewjones372.pelican.pekko")

/**
 * Rendered through core's own JSON tree rather than the configured codec: a
 * codec that has just failed is not the thing to report that it failed.
 */
internal fun errorResponse(raw: Throwable, api: Api?, endpoint: Endpoint<*, *>? = null): HttpResponse {
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
    return HttpResponse.create()
        .withStatus(statusOf(error.status))
        .withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, error.toJson().render()))
        .addHeaders(rendered.headers.map { (n, v) -> RawHeader.create(n, v) })
}
