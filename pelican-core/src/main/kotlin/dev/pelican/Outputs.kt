package dev.pelican

import kotlin.reflect.KType

/**
 * Phantom marker for "the handler produces a stream of [T]".
 *
 * It has no instances and never will. Its only job is to let a backend module
 * bind handlers for exactly the endpoints that stream, in that backend's
 * native type:
 *
 * ```
 * // pelican-pekko
 * infix fun <I, T> Endpoint<I, StreamOf<T>>.streamedNow(f: (I) -> Source<T, NotUsed>)
 *
 * // pelican-ktor
 * infix fun <I, T> Endpoint<I, StreamOf<T>>.streamedNow(f: suspend (I) -> Flow<T>)
 * ```
 */
class StreamOf<T> private constructor()

/** Same idea, for an opaque stream of bytes. */
class ByteStream private constructor()

/**
 * What an endpoint returns: a status, a media type, and the type of the
 * payload. How that type becomes bytes is decided later, by the [Codecs] the
 * [Api] is configured with.
 */
sealed class Output<R> {
    abstract val status: Int
    abstract val mediaType: String?

    /** The payload type, or null when there is no body. */
    open val payloadType: KType? = null
}

class JsonOutput<T> @PublishedApi internal constructor(
    override val status: Int,
    val type: KType,
) : Output<T>() {
    override val mediaType = "application/json"
    override val payloadType get() = type
}

class TextOutput @PublishedApi internal constructor(override val status: Int) : Output<String>() {
    override val mediaType = "text/plain"
}

class EmptyOutput @PublishedApi internal constructor(override val status: Int) : Output<Unit>() {
    override val mediaType: String? = null
}

/** Newline-delimited JSON: one document per line, flushed as produced. */
class NdjsonOutput<T> @PublishedApi internal constructor(
    override val status: Int,
    val type: KType,
) : Output<StreamOf<T>>() {
    override val mediaType = "application/x-ndjson"
    override val payloadType get() = type

    fun frame(codec: BodyCodec<T>, value: T): String = codec.encodeToString(value) + "\n"
}

/** Server-sent events. */
class SseOutput<T> @PublishedApi internal constructor(
    override val status: Int,
    val type: KType,
    val eventName: String?,
) : Output<StreamOf<T>>() {
    override val mediaType = "text/event-stream"
    override val payloadType get() = type

    fun frame(codec: BodyCodec<T>, value: T): String = buildString {
        if (eventName != null) append("event: ").append(eventName).append('\n')
        append("data: ").append(codec.encodeToString(value)).append("\n\n")
    }
}

/**
 * A streamed JSON *array* — `[{...},{...}]` rather than one document per line.
 *
 * Unlike NDJSON and SSE, core does not frame this one: the separators are the
 * backend's business, because Pekko already has `EntityStreamingSupport.json()`
 * for exactly this and reimplementing it would be worse. A backend without an
 * equivalent has to supply its own framing.
 */
class JsonArrayOutput<T> @PublishedApi internal constructor(
    override val status: Int,
    val type: KType,
) : Output<StreamOf<T>>() {
    override val mediaType = "application/json"
    override val payloadType get() = type
}

class ByteStreamOutput @PublishedApi internal constructor(
    override val status: Int,
    override val mediaType: String,
) : Output<ByteStream>()
