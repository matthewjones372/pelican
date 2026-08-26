package io.github.matthewjones372.pelican.spi

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.BodyDecodeFailure
import io.github.matthewjones372.pelican.BodyInput
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.CookieParam
import io.github.matthewjones372.pelican.DeclaredResponses
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.FormBody
import io.github.matthewjones372.pelican.HeaderParam
import io.github.matthewjones372.pelican.JSON_MEDIA_TYPE
import io.github.matthewjones372.pelican.JsonBody
import io.github.matthewjones372.pelican.ListStyle
import io.github.matthewjones372.pelican.MediaOutput
import io.github.matthewjones372.pelican.MultipartBody
import io.github.matthewjones372.pelican.NdjsonBody
import io.github.matthewjones372.pelican.NegotiatedBody
import io.github.matthewjones372.pelican.NegotiatedOutput
import io.github.matthewjones372.pelican.NotAcceptable
import io.github.matthewjones372.pelican.Output
import io.github.matthewjones372.pelican.PayloadTooLarge
import io.github.matthewjones372.pelican.PlainCodec
import io.github.matthewjones372.pelican.QueryParam
import io.github.matthewjones372.pelican.RawBody
import io.github.matthewjones372.pelican.UnsupportedMediaType
import io.github.matthewjones372.pelican.decodeAll
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.formCodec
import io.github.matthewjones372.pelican.mediaType
import io.github.matthewjones372.pelican.representations
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.IdentityHashMap
/**
 * A strict request body as the text a codec reads, refusing anything past
 * [limit] with [PayloadTooLarge].
 *
 * Bytes rather than characters. `String.length` counts UTF-16 code units, so a
 * limit checked against it admits about three times as much CJK as it says, and
 * a body has to be whole in memory before it can be counted at all — which is
 * the thing the limit exists to prevent. Counting as it reads refuses on the
 * byte that crosses the line.
 *
 * A little past the limit is still read before the refusal goes out. Unread
 * bytes are bytes the client is still writing, and answering mid-upload gives it
 * a broken pipe instead of the status; the same reason, and the same overrun,
 * that the multipart reader drains.
 */
fun readStrictBody(input: InputStream, limit: Long): String {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(STRICT_READ_BUFFER_BYTES)
    var remaining = limit
    while (true) {
        val read = input.read(buffer, 0, buffer.size)
        if (read < 0) return String(out.toByteArray(), StandardCharsets.UTF_8)
        if (read > remaining) {
            drain(input, STRICT_DRAIN_OVERRUN_BYTES)
            throw PayloadTooLarge(limit)
        }
        remaining -= read
        out.write(buffer, 0, read)
    }
}

/** Reads and discards up to [bytes], so the connection is whole when the refusal goes out. */
private fun drain(input: InputStream, bytes: Long) {
    val scratch = ByteArray(STRICT_READ_BUFFER_BYTES)
    var remaining = bytes
    while (remaining > 0) {
        val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
        if (read < 0) return
        remaining -= read
    }
}

private const val STRICT_READ_BUFFER_BYTES = 4096

/** Sixty-four kilobytes, the same overrun the multipart reader allows. */
private const val STRICT_DRAIN_OVERRUN_BYTES: Long = 64L * 1024L

/**
 * How a request body is read, once the request says what it is: one entry per
 * media type the endpoint declared. The choosing and the 415 live here so that
 * three backends cannot answer a `Content-Type` three ways.
 */
class RequestBodyCodecs internal constructor(private val byMediaType: Map<String, BodyCodec<Any?>>) {

    /**
     * The body as the value it decodes to.
     */
    fun decode(contentType: String?, text: String): Any? {
        val codec = select(contentType)
        return try {
            codec.decodeFromString(text)
        } catch (t: BodyDecodeFailure) {
            throw t
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            throw BodyDecodeFailure(t.message ?: "Could not decode the request body", t)
        }
    }

    private fun select(contentType: String?): BodyCodec<Any?> {
        byMediaType.values.singleOrNull()?.let { return it }

        val declared = contentType?.substringBefore(';')?.trim()?.lowercase()
        return byMediaType[declared] ?: throw UnsupportedMediaType(
            "The request body arrived as ${declared ?: "no media type at all"}, and this endpoint " +
                "reads ${byMediaType.keys.joinToString(" or ")}. Send one of those in Content-Type.",
        )
    }
}

/**
 * Which codec reads this request body, or null for the ones no codec reads — a
 * raw stream, a multipart envelope, no body. A decision about descriptions
 * rather than about a server, so it is here and not in each interpreter.
 */
fun Codecs.requestBodyCodec(input: BodyInput<*>?): RequestBodyCodecs? = when (input) {
    // A streamed body is here because a frame of it is read exactly as a strict
    // body is — one document, one codec, wrapped the same way when it will not
    // decode. What differs is how many times, which is `NdjsonFrames`' business.
    is JsonBody<*>, is FormBody<*>, is NdjsonBody<*> ->
        RequestBodyCodecs(mapOf(input.mediaType to oneBodyCodec(input)))

    // Resolved here rather than on the request that picks one, so an unreadable
    // encoding is a startup failure and not a 500 for whoever chose it.
    is NegotiatedBody<*> -> RequestBodyCodecs(
        input.alternatives.associate { it.mediaType to oneBodyCodec(it) },
    )

    null, is RawBody, is MultipartBody -> null
}

/**
 * The frame reader for a streamed request body, or null where the body is not
 * framed. One per request, because it holds the frame a chunk stopped inside.
 */
fun Api.ndjsonFrames(endpoint: Endpoint<*, *>, codecs: RequestBodyCodecs?, contentType: String?): NdjsonFrames =
    NdjsonFrames(
        checkNotNull(codecs) { "No codec was resolved for the body of $endpoint" },
        contentType,
        maxFrameBytes,
    )

/**
 * A writer per response an endpoint may answer with, keyed by identity: every
 * representation of every declared success, and every declared failure. Two
 * responses can carry one payload type, and a negotiated one carries the same
 * type under several encodings, so identity is what tells them apart.
 *
 * Resolved once, ahead of any request, by all four interpreters — a media type
 * nothing can write is then a startup failure rather than a 500 for whoever
 * asked for it.
 */
fun Codecs.responseCodecs(output: Output<*>): Map<Any, BodyCodec<Any?>> {
    val successes = output.representations().mapNotNull { out ->
        out.payloadType?.let { out as Any to codec<Any?>(it, out.writtenAs()) }
    }
    val failures = (output as? DeclaredResponses<*, *>)?.failures.orEmpty()
        .map { failure -> failure as Any to codec<Any?>(failure.type) }

    return (successes + failures).associateTo(IdentityHashMap<Any, BodyCodec<Any?>>()) { it }
}

/**
 * Which encoding writes this response. JSON unless the response says
 * otherwise: a streamed one frames JSON documents under a media type of its
 * own, and only [MediaOutput] declares the encoding itself.
 */
private fun Output<*>.writtenAs(): String =
    if (this is MediaOutput<*>) mediaType else JSON_MEDIA_TYPE

/** What reads a body of one media type. Every alternative is one of these. */
private fun Codecs.oneBodyCodec(input: BodyInput<*>): BodyCodec<Any?> = when (input) {
    is JsonBody<*> -> codec(input.type)
    is NdjsonBody<*> -> codec(input.type)
    is FormBody<*> -> formCodec(input.type)
    else -> error("$input is not a body a codec reads")
}

/**
 * What a multi-valued parameter contributes, given every occurrence under its
 * name. Shared by the three locations because the subtle part is common: an
 * empty occurrence is not an element, so a list that comes out empty is a
 * parameter the caller did not send.
 */
fun QueryParam<*>.decodeList(wire: List<String>): Any? =
    listValue(name, codec, listStyle, required, default, "query parameter", wire)

fun HeaderParam<*>.decodeList(wire: List<String>): Any? =
    listValue(name, codec, listStyle, required, default, "header", wire)

fun CookieParam<*>.decodeList(wire: List<String>): Any? =
    listValue(name, codec, listStyle, required, default, "cookie", wire)

@Suppress("LongParameterList") // The declaration's facets, from three classes with no common supertype.
private fun listValue(
    name: String,
    codec: PlainCodec<*>,
    style: ListStyle?,
    required: Boolean,
    default: Any?,
    noun: String,
    wire: List<String>,
): Any? {
    val values = codec.decodeAll(name, checkNotNull(style) { "'$name' was not declared as a list" }, wire)
    return when {
        values.isNotEmpty() -> values
        required -> throw ApiException(400, "Missing required $noun '$name'")
        else -> default
    }
}

/**
 * Whether a caller sending these `Accept` field lines would take any of
 * [produced]. Absent, empty or unparseable means yes — the header is often set
 * by a proxy or an SDK rather than by the caller. Empty [produced] — a 204 —
 * has nothing to negotiate.
 */
fun acceptable(accept: List<String>, produced: Set<String>): Boolean {
    if (produced.isEmpty()) return true

    val ranges = accept.flatMap { it.split(',') }.mapNotNull(::parseRange)
    if (ranges.isEmpty()) return true

    return produced.any { type -> qualityOf(type, ranges) > 0.0 }
}

/**
 * Which representation of a negotiated response goes out, given the `Accept`
 * field lines this request arrived with.
 *
 * Declaration order is the answer where the caller expressed no preference —
 * no header, an unparseable one, `*&#47;*` — and breaks ties between equally
 * acceptable ones. A caller that will take none of them gets [NotAcceptable],
 * the same 406 [acceptable] answers before the handler runs; it is reachable
 * here because a group's `Accept` may exclude every representation while
 * another declared response is still acceptable.
 */
fun NegotiatedOutput<*>.selectedFor(accept: List<String>): Output<*> {
    val ranges = accept.flatMap { it.split(',') }.mapNotNull(::parseRange)
    if (ranges.isEmpty()) return alternatives.first()

    return alternatives
        .map { it to qualityOf(checkNotNull(it.mediaType), ranges) }
        .filter { (_, quality) -> quality > 0.0 }
        // The first of the best, so declaration order is what settles a tie.
        .maxByOrNull { (_, quality) -> quality }
        ?.first
        ?: throw NotAcceptable(produces)
}

/**
 * One entry of an `Accept` header. [specificity] is RFC 9110 §12.5.1's most
 * specific reference — exact beats `type/&#42;` beats wildcard — and decides
 * which range supplies the quality.
 */
private class AcceptRange(val main: String, val sub: String, val quality: Double) {
    val specificity: Int = when {
        main == "*" -> 0
        sub == "*" -> 1
        else -> 2
    }

    fun matches(mainType: String, subType: String): Boolean =
        (main == "*" || main.equals(mainType, ignoreCase = true)) &&
            (sub == "*" || sub.equals(subType, ignoreCase = true))
}

/**
 * The `q` of the most specific range matching [mediaType], or zero. Ties take
 * the highest quality, so naming a range twice cannot lower it.
 */
private fun qualityOf(mediaType: String, ranges: List<AcceptRange>): Double {
    val slash = mediaType.indexOf('/')
    val mainType = if (slash < 0) mediaType else mediaType.substring(0, slash)
    val subType = if (slash < 0) "" else mediaType.substring(slash + 1).substringBefore(';').trim()

    return ranges.asSequence()
        .filter { it.matches(mainType, subType) }
        .maxWithOrNull(compareBy({ it.specificity }, { it.quality }))
        ?.quality
        ?: 0.0
}

/** Null for an entry that is not a media range at all; such an entry is ignored. */
private fun parseRange(entry: String): AcceptRange? {
    val parts = entry.split(';')
    val range = parts[0].trim()
    if (range.isEmpty()) return null

    val slash = range.indexOf('/')
    if (slash <= 0 || slash == range.length - 1) return null

    val quality = parts.asSequence()
        .drop(1)
        .map { it.trim() }
        .firstOrNull { it.startsWith("q=", ignoreCase = true) }
        ?.substring(2)
        ?.trim()
        ?.toDoubleOrNull()
        ?: 1.0

    return AcceptRange(range.substring(0, slash), range.substring(slash + 1), quality.coerceIn(0.0, 1.0))
}

/** The request headers a backend reads to answer a preflight. */
object CorsHeaders {
    const val ORIGIN = "Origin"
    const val REQUEST_METHOD = "Access-Control-Request-Method"
}
