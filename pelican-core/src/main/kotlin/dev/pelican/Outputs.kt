package dev.pelican

import kotlin.reflect.KType
import kotlin.reflect.typeOf

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
 * What an endpoint answers with: a status, a media type, and the type of the
 * payload. How that type becomes bytes is decided later, by the [Codecs] the
 * [Api] is configured with.
 *
 * One of these is a *whole response* rather than half of one, which is what
 * makes several of them declarable side by side — see [or] and [invoke].
 */
sealed class Output<R> {
    abstract val status: Int
    abstract val mediaType: String?

    /** The payload type, or null when there is no body. */
    open val payloadType: KType? = null

    /**
     * Headers belonging to *this* response and no other — a `Location` on a
     * 201 that a 200 beside it must not carry.
     *
     * Declared here rather than with `emits(...)` for the same reason a
     * failure's are declared on the failure: `emits(...)` is the endpoint's
     * list, documented on every response it sends and settable from any of
     * them. An endpoint that answers one status one way and another another
     * way has headers that belong to one of the two.
     *
     * Empty for the streaming outputs, which cannot be alternatives at all —
     * see [FallibleOutput].
     */
    open val headers: List<ResponseHeader<*>> get() = emptyList()

    /**
     * Names this response as the one the handler is producing:
     *
     * ```
     * val created  = json<Order>(status = 201, location)
     * val accepted = empty(status = 202)
     *
     * placeOrder handledOneOf { (id, req) ->
     *     if (Store.canPlaceNow(id)) created(Store.create(id, req), location of "/orders/7")
     *     else                       accepted()
     * }
     * ```
     *
     * The declaration is what fixes the status, exactly as invoking an
     * [ErrorOutput] does — so `json<Order>(200)` and `json<Order>(201)` stay
     * distinguishable although a payload cannot tell them apart. The headers
     * are checked against what this response declared, by the same rule and
     * with the same messages as a failure's; see [ErrorOutput.invoke].
     */
    operator fun invoke(value: R, vararg values: HeaderValue): Outcome<Nothing, R> =
        Outcome.Ok(value, this, encodeDeclaredHeaders(this, headers, values))

    /** `json:201`, `empty:202` — enough to name a response in a refusal. */
    override fun toString(): String =
        javaClass.simpleName.removeSuffix("Output").replaceFirstChar(Char::lowercaseChar) + ":" + status
}

/**
 * The empty response named without a payload — `accepted()` rather than
 * `accepted(Unit)`.
 *
 * An extension rather than an overload of [Output.invoke] because the member
 * always wants a value, and `Unit` is the one payload nobody should have to
 * write down.
 */
operator fun EmptyOutput.invoke(vararg values: HeaderValue): Outcome<Nothing, Unit> =
    Outcome.Ok(Unit, this, encodeDeclaredHeaders(this, headers, values))

class JsonOutput<T> @PublishedApi internal constructor(
    override val status: Int,
    val type: KType,
    override val headers: List<ResponseHeader<*>> = emptyList(),
) : Output<T>() {
    override val mediaType = "application/json"
    override val payloadType get() = type
}

class TextOutput @PublishedApi internal constructor(
    override val status: Int,
    override val headers: List<ResponseHeader<*>> = emptyList(),
) : Output<String>() {
    override val mediaType = "text/plain"
}

class EmptyOutput @PublishedApi internal constructor(
    override val status: Int,
    override val headers: List<ResponseHeader<*>> = emptyList(),
) : Output<Unit>() {
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

/*
 * The value outputs, declared outside an endpoint block so a handler can name
 * one — the same three functions [EndpointBuilder] has, spelled the same way,
 * exactly as `errorJson` is spelled the same way in both places.
 *
 * An output written inside the block is an expression, and an expression has no
 * name for a handler to invoke. So an endpoint declaring several responses
 * declares them as values first:
 *
 * ```
 * val created  = json<Order>(status = 201, location)
 * val accepted = empty(status = 202)
 *
 * val placeOrder = endpoint(userId, newOrder) {
 *     post("users" / userId / "orders")
 *     created or accepted
 * }
 * ```
 *
 * Inside a block the member wins, so nothing about the single-response case
 * changes: `json<Order>()` there is the same call it always was.
 */

/** A single JSON value. See [EndpointBuilder.json]. */
inline fun <reified T> json(status: Int = 200, vararg headers: ResponseHeader<*>): JsonOutput<T> =
    JsonOutput(status, typeOf<T>(), headers.toList())

/** Plain text. See [EndpointBuilder.text]. */
fun text(status: Int = 200, vararg headers: ResponseHeader<*>): TextOutput =
    TextOutput(status, headers.toList())

/** No body at all. See [EndpointBuilder.empty]. */
fun empty(status: Int = 204, vararg headers: ResponseHeader<*>): EmptyOutput =
    EmptyOutput(status, headers.toList())
