package dev.pelican.pekko

import dev.pelican.*
import org.apache.pekko.NotUsed
import org.apache.pekko.http.javadsl.common.EntityStreamingSupport
import org.apache.pekko.http.javadsl.model.*
import org.apache.pekko.http.javadsl.model.headers.RawHeader
import org.apache.pekko.stream.javadsl.Source
import org.apache.pekko.util.ByteString
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.reflect.KClass

internal val NDJSON: ContentType.NonBinary =
    MediaTypes.customWithFixedCharset("application", "x-ndjson", HttpCharsets.UTF_8, HashMap(), false)
        .toContentType()

internal val EVENT_STREAM: ContentType.NonBinary = MediaTypes.TEXT_EVENT_STREAM.toContentType()

/**
 * Turns a described [Output] plus a handler's result into a Pekko response.
 *
 * Note the split of responsibilities. Core knows how to render one element —
 * `NdjsonOutput.frame`, `SseOutput.frame` — and this file knows how to put
 * elements on a socket. Streaming outputs map the source element by element
 * into a chunked entity, so back-pressure runs from the socket all the way
 * back to the source.
 *
 * [JsonArrayOutput] is the exception: Pekko already frames a stream of JSON
 * documents as an array, and reimplementing brace-and-comma handling in core
 * would be strictly worse than calling it.
 *
 * [codecs] were resolved for this endpoint when the route was built, not
 * looked up per request.
 */
@Suppress("UNCHECKED_CAST")
internal fun buildResponse(out: Output<*>, value: Any?, codecs: EndpointCodecs): HttpResponse {
    // Every output carrying a payload type had its codec resolved when the
    // route was built, so a null here is a bug in that resolution rather than
    // anything a request can provoke.
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

    val entity: ResponseEntity = when (out) {
        is JsonOutput<*> -> HttpEntities.create(
            ContentTypes.APPLICATION_JSON,
            payload().encodeToString(value),
        )

        is TextOutput -> HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, value as String)

        is EmptyOutput -> HttpEntities.EMPTY

        is NdjsonOutput<*> -> {
            val o = out as NdjsonOutput<Any?>
            val c = payload()
            HttpEntities.createChunked(NDJSON, elements(value).map { ByteString.fromString(o.frame(c, it)) })
        }

        is SseOutput<*> -> {
            val o = out as SseOutput<Any?>
            val c = payload()
            HttpEntities.createChunked(EVENT_STREAM, elements(value).map { ByteString.fromString(o.frame(c, it)) })
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

        // Unreachable: handled above, before any payload is touched.
        is FallibleOutput<*, *> -> error("Unreachable")
    }

    return HttpResponse.create().withStatus(StatusCodes.get(out.status)).withEntity(entity)
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
): HttpResponse {
    val declared = err.declared
    check(out.failures.any { it === declared }) {
        "$declared was returned by a handler but $out never declared it"
    }
    val cls = declared.type.classifier as? KClass<*>
    check(cls == null || cls.isInstance(err.error)) {
        "$declared carries ${declared.type} but the handler returned ${err.error?.let { it::class }}"
    }
    val codec = checkNotNull(codecs.failures[declared]) { "No codec was resolved for $declared" }
    return HttpResponse.create()
        .withStatus(StatusCodes.get(declared.status))
        .withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, codec.encodeToString(err.error)))
}

@Suppress("UNCHECKED_CAST")
private fun elements(value: Any?): Source<Any?, NotUsed> = value as Source<Any?, NotUsed>

private fun contentTypeOf(mediaType: String): ContentType {
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

private val log: Logger = LoggerFactory.getLogger("dev.pelican.pekko")

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
internal fun errorResponse(raw: Throwable, api: Api?, endpoint: Endpoint<*, *>? = null): HttpResponse {
    val rendered = renderError(raw, api?.exposeInternalErrors ?: false)

    rendered.unexpected?.let { failure ->
        val hook = api?.onServerError
        if (hook != null) hook(rendered.reference!!, endpoint, failure)
        else log.error("Unhandled failure in {} [ref {}]", endpoint ?: "?", rendered.reference, failure)
    }

    val error = rendered.error
    return HttpResponse.create()
        .withStatus(StatusCodes.get(error.status))
        .withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, error.toJson().render()))
        .addHeaders(rendered.headers.map { (n, v) -> RawHeader.create(n, v) })
}
