package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.acceptable
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration

/**
 * Phantom marker for "the handler produces a stream of [T]". No instances: its
 * job is to let each backend bind handlers for exactly the streaming
 * endpoints, in that backend's own type.
 */
class StreamOf<T> private constructor()

/** Same idea, for an opaque stream of bytes. */
class ByteStream private constructor()

/**
 * What an endpoint answers with: a status, a media type, a payload type. How
 * that becomes bytes is the [Codecs]' decision, later.
 */
sealed class Output<R> {
    abstract val status: Int
    abstract val mediaType: String?

    /**
     * What this response means, for the document. Null takes the wording the
     * media type implies — "Success.", "No content." and the rest — which is
     * all there was until an endpoint could declare two successes and both
     * arrived saying the same thing.
     */
    open val description: String? get() = null

    /** The payload type, or null when there is no body. */
    open val payloadType: KType? = null

    /**
     * What negotiation is answered against, worked out once because an output
     * never changes after the endpoint is described. See [acceptable].
     */
    open val produces: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) { setOfNotNull(mediaType) }

    /**
     * Headers belonging to *this* response and no other — a `Location` on a
     * 201 that a 200 beside it must not carry.
     */
    open val headers: List<ResponseHeader<*>> get() = emptyList()

    /**
     * Names this response as the one the handler is producing —
     * `created(order, location of "/orders/7")`.
     */
    operator fun invoke(value: R, vararg values: HeaderValue): Outcome<Nothing, R> =
        Outcome.Ok(value, this, encodeDeclaredHeaders(this, headers, values))

    /** `json:201`, `empty:202` — enough to name a response in a refusal. */
    override fun toString(): String =
        javaClass.simpleName.removeSuffix("Output").replaceFirstChar(Char::lowercaseChar) + ":" + status
}

/**
 * `accepted()` rather than `accepted(Unit)`. An extension rather than an
 * overload because the member always wants a value.
 */
operator fun EmptyOutput.invoke(vararg values: HeaderValue): Outcome<Nothing, Unit> =
    Outcome.Ok(Unit, this, encodeDeclaredHeaders(this, headers, values))

/** HTTP status codes run from here to [MAX_STATUS]. */
private const val MIN_STATUS = 100
private const val MAX_STATUS = 599

/** No entity may be sent with these, so nothing that carries one may declare them. */
private const val NO_CONTENT = 204
private const val NOT_MODIFIED = 304
private const val LAST_INFORMATIONAL = 199

/**
 * Checked where the response is declared, so a bad status stops the service
 * coming up rather than turning one request into an unexplained 500.
 */
internal fun checkStatus(owner: String, status: Int, carriesBody: Boolean) {
    require(status in MIN_STATUS..MAX_STATUS) {
        "$owner declares status $status, and HTTP status codes run from $MIN_STATUS to $MAX_STATUS."
    }
    require(!carriesBody || statusAllowsBody(status)) {
        "$owner declares status $status, which cannot carry a body. " +
            "Use empty(status = $status) for a response with no payload."
    }
}

/** 1xx, 204 and 304 are defined to have no entity; everything else may have one. */
internal fun statusAllowsBody(status: Int): Boolean =
    status > LAST_INFORMATIONAL && status != NO_CONTENT && status != NOT_MODIFIED

class JsonOutput<T> @PublishedApi internal constructor(
    override val status: Int,
    val type: KType,
    override val headers: List<ResponseHeader<*>> = emptyList(),
    override val description: String? = null,
) : Output<T>() {
    override val mediaType = "application/json"
    override val payloadType get() = type

    init { checkStatus(toString(), status, carriesBody = true) }
}

class TextOutput @PublishedApi internal constructor(
    override val status: Int,
    override val headers: List<ResponseHeader<*>> = emptyList(),
    override val description: String? = null,
) : Output<String>() {
    override val mediaType = "text/plain"

    init { checkStatus(toString(), status, carriesBody = true) }
}

class EmptyOutput @PublishedApi internal constructor(
    override val status: Int,
    override val headers: List<ResponseHeader<*>> = emptyList(),
    override val description: String? = null,
) : Output<Unit>() {
    override val mediaType: String? = null

    init { checkStatus(toString(), status, carriesBody = false) }
}

/** Newline-delimited JSON: one document per line, flushed as produced. */
class NdjsonOutput<T> @PublishedApi internal constructor(
    override val status: Int,
    val type: KType,
    override val description: String? = null,
) : Output<StreamOf<T>>() {
    override val mediaType = "application/x-ndjson"
    override val payloadType get() = type

    fun frame(codec: BodyCodec<T>, value: T): String = codec.encodeToString(value) + "\n"

    init { checkStatus(toString(), status, carriesBody = true) }
}

/** Server-sent events. */
class SseOutput<T> @PublishedApi internal constructor(
    override val status: Int,
    val type: KType,
    val eventName: String?,
    /**
     * How long the stream may sit idle before a comment proves it is still
     * there, or null to send nothing. An idle SSE connection is
     * indistinguishable from a dead one, and proxies drop it accordingly.
     */
    val keepAlive: Duration? = null,
    override val description: String? = null,
) : Output<StreamOf<T>>() {
    override val mediaType = "text/event-stream"
    override val payloadType get() = type

    fun frame(codec: BodyCodec<T>, value: T): String = buildString {
        if (eventName != null) append("event: ").append(eventName).append('\n')
        append("data: ").append(codec.encodeToString(value)).append("\n\n")
    }

    init {
        checkStatus(toString(), status, carriesBody = true)
        require(keepAlive == null || keepAlive > Duration.ZERO) {
            "keepAlive is how long the stream may be idle, so it is a positive duration or null; got $keepAlive"
        }
    }

    companion object {
        /** An SSE comment: a colon line, which every conformant client discards. */
        const val KEEP_ALIVE_FRAME: String = ":\n\n"
    }
}

/**
 * A streamed JSON array. Core does not frame this one: Pekko already has
 * `EntityStreamingSupport.json()`, so the separators are the backend's.
 */
class JsonArrayOutput<T> @PublishedApi internal constructor(
    override val status: Int,
    val type: KType,
    override val description: String? = null,
) : Output<StreamOf<T>>() {
    override val mediaType = "application/json"
    override val payloadType get() = type

    init { checkStatus(toString(), status, carriesBody = true) }
}

class ByteStreamOutput @PublishedApi internal constructor(
    override val status: Int,
    override val mediaType: String,
    override val description: String? = null,
) : Output<ByteStream>() {
    init { checkStatus(toString(), status, carriesBody = true) }
}

/**
 * A value written as a media type that is not JSON — `text/csv`, `text/xml`.
 * The description holds the type, as [JsonOutput] does; what writes it comes
 * from [CodecFactory.codec], which is where a service answering `text/csv`
 * puts the writer for it.
 */
class MediaOutput<T> @PublishedApi internal constructor(
    override val status: Int,
    override val mediaType: String,
    val type: KType,
    override val headers: List<ResponseHeader<*>> = emptyList(),
    override val description: String? = null,
) : Output<T>() {
    override val payloadType get() = type

    init {
        checkStatus(toString(), status, carriesBody = true)
        val slash = mediaType.indexOf('/')
        require(slash > 0 && slash < mediaType.length - 1 && '*' !in mediaType) {
            "'$mediaType' is not a media type a response can be written as. It is one concrete " +
                "type/subtype — \"text/csv\" — since what goes out has to be something in particular."
        }
    }

    override fun toString() = "$mediaType:$status"
}

/**
 * One response written several ways, the caller's `Accept` picking which: the
 * same value under the same status, differing only in how it reaches the wire.
 *
 * See [negotiated]. Which one goes out is the interpreter's answer, from
 * `selectedFor`, so a handler goes on returning a value.
 */
class NegotiatedOutput<T> internal constructor(
    /** In declaration order. The first is what a caller expressing no preference gets. */
    val alternatives: List<Output<out T>>,
) : Output<T>() {

    /** What the group reports as itself, since the group is one response. */
    private val first: Output<out T> get() = alternatives.first()

    override val status get() = first.status
    override val mediaType get() = first.mediaType
    override val payloadType get() = first.payloadType
    override val description get() = alternatives.firstNotNullOfOrNull { it.description }

    override val produces: Set<String> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        alternatives.mapNotNull { it.mediaType }.toSet()
    }

    init {
        require(alternatives.size > 1) {
            "negotiated(...) offers a caller a choice, and this offers one representation: $first. " +
                "Declare that response on its own."
        }

        alternatives.forEach { alternative ->
            require(alternative !is FallibleOutput<*, *>) {
                "$alternative declares responses of its own, so it is not one rendering of one value. " +
                    "Declare failures beside the group — negotiated(...) orFail ... — rather than inside it."
            }
            require(!alternative.streams()) {
                "$alternative streams, and a stream is handed over in the backend's own type rather than " +
                    "written from a value. Declare the stream as the one response."
            }
            require(alternative.mediaType != null && alternative.payloadType != null) {
                "$alternative describes no payload of its own, so nothing could write it as one rendering " +
                    "of a value. Each alternative names a media type and the type written as it: " +
                    "json<T>(200), media<T>(\"text/csv\", 200)."
            }
        }

        val statuses = alternatives.map { it.status }.distinct()
        require(statuses.size == 1) {
            "A negotiated response is one response, and these are declared for status " +
                "${statuses.joinToString()}. `Accept` picks a representation and never a status: give them " +
                "one status, or declare them as the separate responses they are."
        }

        val clash = alternatives.groupBy { it.mediaType }.filterValues { it.size > 1 }.keys
        require(clash.isEmpty()) {
            "A response goes out as one Content-Type, so nothing could pick between two representations " +
                "both written as ${clash.joinToString()}."
        }

        val types = alternatives.map { it.payloadType }.distinct()
        require(types.size == 1) {
            "A negotiated response is one value written several ways, and these carry " +
                "${types.joinToString()}. Two payloads are two responses: give them different statuses, " +
                "or answer them from different endpoints."
        }

        val withHeaders = alternatives.filter { it.headers.isNotEmpty() }
        require(withHeaders.isEmpty()) {
            "${withHeaders.joinToString()} declares headers of its own, and a header belongs to the " +
                "response rather than to one rendering of it — a caller sending a different `Accept` would " +
                "stop receiving it. Declare them with emits(...) on the endpoint."
        }
    }

    override fun toString() =
        "negotiated(" + alternatives.joinToString("|") { it.mediaType.orEmpty() } + "):$status"
}

/**
 * The responses a codec may be asked to write for this output: each declared
 * success, and each representation inside a negotiated one.
 */
fun Output<*>.representations(): List<Output<*>> = when (this) {
    is FallibleOutput<*, *> -> successes.flatMap { it.representations() }
    is NegotiatedOutput<*> -> alternatives
    else -> listOf(this)
}

/*
 * The same three outputs EndpointBuilder has, declared outside a block so a
 * handler can name one: an output written inside the block is an expression
 * and has no name to invoke. Inside a block the member wins.
 */

/** A single JSON value. See [EndpointBuilder.json]. */
inline fun <reified T> json(
    status: Int = 200,
    vararg headers: ResponseHeader<*>,
    description: String? = null,
): JsonOutput<T> = JsonOutput(status, typeOf<T>(), headers.toList(), description)

/** Plain text. See [EndpointBuilder.text]. */
fun text(status: Int = 200, vararg headers: ResponseHeader<*>, description: String? = null): TextOutput =
    TextOutput(status, headers.toList(), description)

/** No body at all. See [EndpointBuilder.empty]. */
fun empty(status: Int = 204, vararg headers: ResponseHeader<*>, description: String? = null): EmptyOutput =
    EmptyOutput(status, headers.toList(), description)

/** A value written as [mediaType]. See [EndpointBuilder.media]. */
inline fun <reified T> media(
    mediaType: String,
    status: Int = 200,
    vararg headers: ResponseHeader<*>,
    description: String? = null,
): MediaOutput<T> = MediaOutput(status, mediaType, typeOf<T>(), headers.toList(), description)

/**
 * The same value under one status, written each of several ways —
 * `negotiated(json<Report>(200), media<Report>("text/csv", 200))`. The
 * caller's `Accept` picks one; a caller that takes none of them is refused
 * with the same 406 the endpoint answers before the handler runs.
 *
 * Flattened, so `negotiated(a, negotiated(b, c))` is three alternatives rather
 * than nested pairs — otherwise "the first" would depend on where the
 * parentheses fell.
 */
@Suppress("UNCHECKED_CAST")
fun <T> negotiated(first: Output<out T>, vararg others: Output<out T>): NegotiatedOutput<T> =
    NegotiatedOutput(
        (listOf(first) + others).flatMap { out ->
            if (out is NegotiatedOutput<*>) out.alternatives else listOf(out)
        } as List<Output<out T>>,
    )
