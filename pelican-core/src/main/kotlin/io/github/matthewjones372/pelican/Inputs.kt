@file:Suppress("TooManyFunctions") // One declaration per input kind; the list is the vocabulary.

package io.github.matthewjones372.pelican

import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Anything a handler can pull a typed value out of via [Params.get].
 */
sealed interface ParamKey<out T>

// Public because interpreters live in other modules. Read them; mutate nothing.

/** A captured path segment, e.g. the `{userId}` in `/users/{userId}`. */
class PathParam<T> @PublishedApi internal constructor(
    val name: String,
    val codec: PlainCodec<*>,
    val description: String? = null,
) : ParamKey<T> {
    override fun toString() = "path:$name"
}

/** A query string parameter. */
class QueryParam<T> @PublishedApi internal constructor(
    val name: String,
    val codec: PlainCodec<*>,
    val required: Boolean,
    val default: Any?,
    val description: String? = null,
    /** Null for one value; otherwise how the list's elements are told apart. */
    val listStyle: ListStyle? = null,
) : ParamKey<T> {
    override fun toString() = "query:$name"
}

/** A request header. */
class HeaderParam<T> @PublishedApi internal constructor(
    val name: String,
    val codec: PlainCodec<*>,
    val required: Boolean,
    val default: Any?,
    val description: String? = null,
    /**
     * Null for the ordinary case of one value. Otherwise this parameter is
     * declared as a list, [codec] decodes one element of it, and this says how
     * the elements are told apart on the wire.
     */
    val listStyle: ListStyle? = null,
) : ParamKey<T> {
    override fun toString() = "header:$name"
}

/**
 * A cookie read as an ordinary typed input — a locale, a feature flag. Distinct
 * from `apiKeyCookie`, which describes one as a credential and draws a padlock.
 */
class CookieParam<T> @PublishedApi internal constructor(
    val name: String,
    val codec: PlainCodec<*>,
    val required: Boolean,
    val default: Any?,
    val description: String? = null,
    /**
     * Null for the ordinary case of one value. Otherwise this parameter is
     * declared as a list, [codec] decodes one element of it, and this says how
     * the elements are told apart on the wire.
     */
    val listStyle: ListStyle? = null,
) : ParamKey<T> {
    override fun toString() = "cookie:$name"
}

/** A request body. */
sealed class BodyInput<T> : ParamKey<T> {
    abstract val description: String?
}

/** A JSON request body, read strictly and decoded by the configured codec. */
class JsonBody<T> @PublishedApi internal constructor(
    val type: KType,
    override val description: String?,
) : BodyInput<T>() {
    override fun toString() = "body:json"
}

/**
 * An `application/x-www-form-urlencoded` body, decoded into [type]. The wire
 * form carries no types, so [type]'s published schema is what says whether `1`
 * is a number or a string.
 */
class FormBody<T> @PublishedApi internal constructor(
    val type: KType,
    override val description: String?,
) : BodyInput<T>() {
    override fun toString() = "body:form"
}

/**
 * The request body as a back-pressured byte stream; the backend decides the
 * concrete type. Nothing is buffered.
 */
interface ByteStreamHandle

/** The raw request body, never read into memory by the framework. */
class RawBody @PublishedApi internal constructor(
    override val description: String?,
) : BodyInput<ByteStreamHandle>() {
    override fun toString() = "body:stream"
}

/**
 * The request body as a stream of [T]; the backend decides the concrete type,
 * and a handler asks for it in that backend's own terms — `toSource()` on
 * Pekko, `toSequence()` on http4k, `toFlow()` on Ktor.
 *
 * An interface where [StreamOf] on the answering side is a phantom, and for a
 * reason that only applies in this direction: a streamed input is one slot of
 * the handler's argument tuple, beside the path and query parameters it
 * composes with, and no binder can retype one slot of a tuple. So the marker
 * has to be a value the request carries — which is what [ByteStreamHandle]
 * already is for the untyped case.
 */
interface StreamIn<T>

/**
 * Newline-delimited JSON arriving as the request body: one document per line,
 * decoded and handed over as it arrives rather than after it has all arrived.
 */
class NdjsonBody<T> @PublishedApi internal constructor(
    val type: KType,
    override val description: String?,
) : BodyInput<StreamIn<T>>() {
    override fun toString() = "body:ndjson"
}

/**
 * One named field of a `multipart/form-data` body. A [ParamKey] so that parts
 * are ordinary inputs: list them on `endpoint(...)` and the handler receives
 * them typed. The [MultipartBody] holding them is assembled for you.
 */
sealed class MultipartPart<T> : ParamKey<T> {
    abstract val name: String
    abstract val description: String?
}

/** A text field of a multipart body, decoded by a [PlainCodec] like any other named value. */
class TextPart<T> @PublishedApi internal constructor(
    override val name: String,
    val codec: PlainCodec<*>,
    val required: Boolean,
    val default: Any?,
    override val description: String? = null,
) : MultipartPart<T>() {
    override fun toString() = "part:$name"
}

/**
 * A file field of a multipart body. The handler receives an [UploadedFile]
 * either way; [bufferedBytes] is what says where the bytes are when it does.
 */
class FilePart<T> @PublishedApi internal constructor(
    override val name: String,
    val required: Boolean,
    /** What the part is expected to carry, e.g. `image/png`. Documented, not enforced. */
    val contentType: String? = null,
    override val description: String? = null,
    /**
     * Null when the part is handed over as a live window on the request.
     * Otherwise the most of it that will be held in memory; see [bufferedFile].
     */
    val bufferedBytes: Long? = null,
) : MultipartPart<T>() {
    /** Whether reading stops here. Exactly the parts nothing holds whole. */
    val streamed: Boolean get() = bufferedBytes == null

    override fun toString() = "part:$name"
}

/**
 * A `multipart/form-data` body, assembled from the [MultipartPart]s an endpoint
 * declares rather than written down.
 */
class MultipartBody internal constructor(
    val parts: List<MultipartPart<*>>,
    override val description: String? = null,
) : BodyInput<Unit>() {
    val textParts: List<TextPart<*>> get() = parts.filterIsInstance<TextPart<*>>()
    val fileParts: List<FilePart<*>> get() = parts.filterIsInstance<FilePart<*>>()

    /** The files held in memory as they arrive, in declaration order. */
    val bufferedFileParts: List<FilePart<*>> get() = fileParts.filterNot { it.streamed }

    /**
     * The file reading stops at, or null. At most one is declarable, and a
     * client writes it after everything else — see [FilePart.streamed].
     */
    val streamedFilePart: FilePart<*>? get() = fileParts.firstOrNull { it.streamed }

    /**
     * The order a client has to write them: everything read as it arrives,
     * then the one the reader stops at. Here rather than in each client, so
     * neither can build a request its own server refuses.
     */
    val partsInWireOrder: List<MultipartPart<*>>
        get() = parts.filterNot { it is FilePart<*> && it.streamed } + listOfNotNull(streamedFilePart)

    override fun toString() = "body:multipart"
}

/**
 * A request body arriving under any of several media types, all carrying the
 * same payload type: `Content-Type` selects a decode, not a schema. Several
 * schemas under one body stays undescribable, since the handler gets one value.
 */
class NegotiatedBody<T> internal constructor(
    /** In declaration order. A client that has to pick one picks the first. */
    val alternatives: List<BodyInput<T>>,
    override val description: String?,
) : BodyInput<T>() {
    override fun toString() = "body:" + alternatives.joinToString("|") { it.mediaType }
}

/** The one media type a description names, which is why [NegotiatedBody] has none. */
val BodyInput<*>.mediaType: String
    get() = when (this) {
        is JsonBody<*> -> "application/json"
        is FormBody<*> -> "application/x-www-form-urlencoded"
        is MultipartBody -> "multipart/form-data"
        is RawBody -> "application/octet-stream"
        is NdjsonBody<*> -> "application/x-ndjson"
        is NegotiatedBody<*> -> error("$this is several media types; ask its alternatives")
    }

/**
 * The payload type a codec reads this body into, or null where no codec reads
 * the body as one value. A streamed body is the second case even though a codec
 * reads every frame of it: the value it names is [NdjsonBody.type], and one
 * request carries however many of those the caller sends.
 */
val BodyInput<*>.payloadType: KType?
    get() = when (this) {
        is JsonBody<*> -> type
        is FormBody<*> -> type
        is NegotiatedBody<*> -> alternatives.first().payloadType
        is MultipartBody, is RawBody, is NdjsonBody<*> -> null
    }

// ---------------------------------------------------------------- factories

inline fun <reified T : Any> pathParam(name: String, description: String? = null): PathParam<T> =
    PathParam(name, plainCodecFor<T>(), description)

fun <T : Any> pathParam(name: String, codec: PlainCodec<T>, description: String? = null): PathParam<T> =
    PathParam(name, codec, description)

inline fun <reified T : Any> queryParam(name: String, description: String? = null): QueryParam<T> =
    QueryParam(name, plainCodecFor<T>(), required = true, default = null, description = description)

fun <T : Any> queryParam(name: String, codec: PlainCodec<T>, description: String? = null): QueryParam<T> =
    QueryParam(name, codec, required = true, default = null, description = description)

inline fun <reified T : Any> headerParam(name: String, description: String? = null): HeaderParam<T> =
    HeaderParam(name, plainCodecFor<T>(), required = true, default = null, description = description)

fun <T : Any> headerParam(name: String, codec: PlainCodec<T>, description: String? = null): HeaderParam<T> =
    HeaderParam(name, codec, required = true, default = null, description = description)

inline fun <reified T : Any> cookieParam(name: String, description: String? = null): CookieParam<T> =
    CookieParam(name, plainCodecFor<T>(), required = true, default = null, description = description)

fun <T : Any> cookieParam(name: String, codec: PlainCodec<T>, description: String? = null): CookieParam<T> =
    CookieParam(name, codec, required = true, default = null, description = description)

/** Makes the parameter optional; reading it yields `null` when absent. */
@Suppress("UNCHECKED_CAST")
fun <T : Any> QueryParam<T>.optional(): QueryParam<T?> =
    QueryParam<T?>(name, codec, required = false, default = null, description, listStyle)

/** Makes the parameter optional, substituting [value] when absent. */
@Suppress("UNCHECKED_CAST")
fun <T : Any> QueryParam<T>.default(value: T): QueryParam<T> =
    QueryParam(name, codec, required = false, default = value, description, listStyle)

@Suppress("UNCHECKED_CAST")
fun <T : Any> HeaderParam<T>.optional(): HeaderParam<T?> =
    HeaderParam<T?>(name, codec, required = false, default = null, description, listStyle)

fun <T : Any> HeaderParam<T>.default(value: T): HeaderParam<T> =
    HeaderParam(name, codec, required = false, default = value, description, listStyle)

@Suppress("UNCHECKED_CAST")
fun <T : Any> CookieParam<T>.optional(): CookieParam<T?> =
    CookieParam<T?>(name, codec, required = false, default = null, description, listStyle)

fun <T : Any> CookieParam<T>.default(value: T): CookieParam<T> =
    CookieParam(name, codec, required = false, default = value, description, listStyle)

// ------------------------------------------------------- more than one value

/**
 * The spellings differ by location because the honest encodings do: a query
 * string can repeat a name, a header cannot, and RFC 9110 already defines what
 * two lines of one header name mean.
 */

/** Several occurrences of the name: `?tag=a&tag=b`. */
fun <T : Any> QueryParam<T>.repeated(): QueryParam<List<T>> = listed(ListStyle.REPEATED)

/** One occurrence, comma-separated: `?tag=a,b`. */
fun <T : Any> QueryParam<T>.commaSeparated(): QueryParam<List<T>> = listed(ListStyle.COMMA)

/** One occurrence, space-separated: `?tag=a%20b`. OpenAPI's `spaceDelimited`. */
fun <T : Any> QueryParam<T>.spaceSeparated(): QueryParam<List<T>> = listed(ListStyle.SPACE)

/** One occurrence, pipe-separated: `?tag=a|b`. OpenAPI's `pipeDelimited`. */
fun <T : Any> QueryParam<T>.pipeSeparated(): QueryParam<List<T>> = listed(ListStyle.PIPE)

private fun <T : Any> QueryParam<T>.listed(style: ListStyle): QueryParam<List<T>> {
    require(listStyle == null) { "$this already carries a list of values" }
    requireNoScalarDefault(this, default)
    return QueryParam(name, codec, required, default, description, style)
}

/**
 * A header carrying several values: `X-Tags: a,b`, or two header lines, which
 * RFC 9110 defines as the same thing. Both are read; the document describes the
 * comma, which is the form OpenAPI names.
 */
fun <T : Any> HeaderParam<T>.commaSeparated(): HeaderParam<List<T>> {
    require(listStyle == null) { "$this already carries a list of values" }
    requireNoScalarDefault(this, default)
    return HeaderParam(name, codec, required, default, description, ListStyle.COMMA)
}

/**
 * A cookie carrying several values as several pairs: `Cookie: tag=a; tag=b`.
 * No comma spelling, because RFC 6265 excludes the comma from a cookie value.
 */
fun <T : Any> CookieParam<T>.repeated(): CookieParam<List<T>> {
    require(listStyle == null) { "$this already carries a list of values" }
    requireNoScalarDefault(this, default)
    return CookieParam(name, codec, required, default, description, ListStyle.REPEATED)
}

/**
 * A scalar default carried into a list-typed parameter would meet the handler
 * as a ClassCastException far from the declaration, so the order is refused.
 */
private fun requireNoScalarDefault(param: Any, default: Any?) {
    require(default == null) {
        "$param declares a default and then a list of values, which would leave a single $default " +
            "where the handler reads a list. Spread first, then default(listOf(...))."
    }
}

inline fun <reified T> jsonBody(description: String? = null): JsonBody<T> =
    JsonBody(typeOf<T>(), description)

/** An `application/x-www-form-urlencoded` body decoded into [T]. */
inline fun <reified T> formBody(description: String? = null): FormBody<T> =
    FormBody(typeOf<T>(), description)

/**
 * The same payload, read from whichever encoding the caller sent —
 * `formBody<CreateOrder>() or jsonBody<CreateOrder>()`. `Content-Type` picks
 * the codec; an undeclared one is a 415 naming those that were declared.
 */
infix fun <T> BodyInput<T>.or(other: BodyInput<T>): NegotiatedBody<T> {
    val alternatives = (asAlternatives() + other.asAlternatives())

    val types = alternatives.map { alternative ->
        requireNotNull(alternative.payloadType) {
            "A ${alternative.mediaType} body is not decoded into a value, so it cannot be an " +
                "alternative to one that is. Take the body as rawBody() and read it yourself."
        }
    }.distinct()
    require(types.size == 1) {
        "A body read several ways is still one value: these alternatives carry " +
            types.joinToString() + ". Declare the endpoint's body as the one type its handler is " +
            "given, or describe the alternatives as a union with a discriminator."
    }

    val clash = alternatives.groupBy { it.mediaType }.filterValues { it.size > 1 }.keys
    require(clash.isEmpty()) {
        "A request declares one Content-Type, so nothing could pick between two bodies both " +
            "read as ${clash.joinToString()}."
    }

    return NegotiatedBody(alternatives, alternatives.firstNotNullOfOrNull { it.description })
}

/**
 * Flattened, so `a or b or c` is three alternatives rather than nested pairs —
 * otherwise "the first" would depend on where the parentheses fell.
 */
private fun <T> BodyInput<T>.asAlternatives(): List<BodyInput<T>> =
    if (this is NegotiatedBody<T>) alternatives else listOf(this)

fun rawBody(description: String? = null): RawBody = RawBody(description)

/**
 * A streamed request body: one JSON document per line, decoded as it arrives.
 * Nothing is held but the frame being read, which `Api.maxFrameBytes` bounds.
 */
inline fun <reified T> ndjsonIn(description: String? = null): NdjsonBody<T> =
    NdjsonBody(typeOf<T>(), description)

/**
 * A named text field of a multipart body: one string on the wire, so it takes
 * the same codecs and modifiers a query parameter takes.
 */
inline fun <reified T : Any> textPart(name: String, description: String? = null): TextPart<T> =
    TextPart(name, plainCodecFor<T>(), required = true, default = null, description = description)

fun <T : Any> textPart(name: String, codec: PlainCodec<T>, description: String? = null): TextPart<T> =
    TextPart(name, codec, required = true, default = null, description = description)

@Suppress("UNCHECKED_CAST")
fun <T : Any> TextPart<T>.optional(): TextPart<T?> =
    TextPart<T?>(name, codec, required = false, default = null, description = description)

fun <T : Any> TextPart<T>.default(value: T): TextPart<T> =
    TextPart(name, codec, required = false, default = value, description = description)

/**
 * A named file field of a multipart body, read by the handler as a stream.
 */
fun filePart(
    name: String,
    contentType: String? = null,
    description: String? = null,
): FilePart<UploadedFile> = FilePart(name, required = true, contentType = contentType, description = description)

/**
 * A file field held in memory rather than streamed, bounded by [maxBytes].
 * This is what makes a second file part describable: reading stops at a
 * streamed part, so a second could only be reached by holding the first.
 */
fun bufferedFile(
    name: String,
    maxBytes: Long,
    contentType: String? = null,
    description: String? = null,
): FilePart<UploadedFile> {
    require(maxBytes > 0) { "A buffered part holds bytes, so '$name' has to be allowed at least one." }
    return FilePart(
        name,
        required = true,
        contentType = contentType,
        description = description,
        bufferedBytes = maxBytes,
    )
}

/** Makes the file optional; reading it yields `null` when the caller sent no such part. */
fun FilePart<UploadedFile>.optional(): FilePart<UploadedFile?> = FilePart(
    name,
    required = false,
    contentType = contentType,
    description = description,
    bufferedBytes = bufferedBytes,
)
