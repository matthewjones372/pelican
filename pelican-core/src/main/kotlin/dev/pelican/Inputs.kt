package dev.pelican

import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Anything a handler can pull a typed value out of via [Params.get].
 *
 * Keys are compared by identity, so hold on to the value you declare:
 * `queryParam<Int>("limit").optional()` returns a *new* key, and that is the
 * one to register and to read.
 */
sealed interface ParamKey<out T>

// The properties below are public because interpreters live in other modules.
// They are the description's surface area: read them, don't mutate anything.

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
) : ParamKey<T> {
    override fun toString() = "header:$name"
}

/**
 * A cookie sent by the caller, read as an ordinary typed input.
 *
 * Distinct from `apiKeyCookie`, which describes a cookie as a *credential* and
 * draws a padlock. This is the other kind: a locale, a feature flag, an
 * A/B bucket — something a handler wants decoded and the document should
 * describe, and which no security scheme is the honest name for.
 */
class CookieParam<T> @PublishedApi internal constructor(
    val name: String,
    val codec: PlainCodec<*>,
    val required: Boolean,
    val default: Any?,
    val description: String? = null,
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
 * An `application/x-www-form-urlencoded` request body, decoded into [type].
 *
 * It travels as `a=1&b=two`, which carries no types at all — so the shape the
 * document publishes for [type] is what says whether `1` is a number or the
 * string "1". See [formCodec] for what that buys.
 */
class FormBody<T> @PublishedApi internal constructor(
    val type: KType,
    override val description: String?,
) : BodyInput<T>() {
    override fun toString() = "body:form"
}

/**
 * A handle to the request body as a back-pressured byte stream. The backend
 * decides what the concrete stream type is; ask it for one:
 *
 * ```
 * val src: Source<ByteString, Any> = params[rawUpload].toSource()   // pelican-pekko
 * ```
 *
 * Nothing is buffered — the handler consumes it at its own pace.
 */
interface ByteStreamHandle

/** The raw request body, never read into memory by the framework. */
class RawBody @PublishedApi internal constructor(
    override val description: String?,
) : BodyInput<ByteStreamHandle>() {
    override fun toString() = "body:stream"
}

/**
 * One named field of a `multipart/form-data` body.
 *
 * A part is a [ParamKey] rather than something read out of a body object,
 * because that is what makes it an ordinary input: list the parts on
 * `endpoint(...)` and the handler receives them typed and in order, exactly as
 * it receives a query parameter. The [MultipartBody] holding them is assembled
 * for you — nothing declares it by hand.
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
 * A file field of a multipart body. The handler receives an [UploadedFile] and
 * reads it as a stream, so nothing here holds an upload whole.
 */
class FilePart<T> @PublishedApi internal constructor(
    override val name: String,
    val required: Boolean,
    /** What the part is expected to carry, e.g. `image/png`. Documented, not enforced. */
    val contentType: String? = null,
    override val description: String? = null,
) : MultipartPart<T>() {
    override fun toString() = "part:$name"
}

/**
 * A `multipart/form-data` request body, described by its parts.
 *
 * Assembled from the [MultipartPart]s an endpoint declares rather than written
 * down: the parts are the inputs, and this is what the document and the
 * interpreters read to know the body is an envelope rather than a payload.
 */
class MultipartBody internal constructor(
    val parts: List<MultipartPart<*>>,
    override val description: String? = null,
) : BodyInput<Unit>() {
    val textParts: List<TextPart<*>> get() = parts.filterIsInstance<TextPart<*>>()
    val fileParts: List<FilePart<*>> get() = parts.filterIsInstance<FilePart<*>>()

    override fun toString() = "body:multipart"
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
    QueryParam<T?>(name, codec, required = false, default = null, description = description)

/** Makes the parameter optional, substituting [value] when absent. */
@Suppress("UNCHECKED_CAST")
fun <T : Any> QueryParam<T>.default(value: T): QueryParam<T> =
    QueryParam(name, codec, required = false, default = value, description = description)

@Suppress("UNCHECKED_CAST")
fun <T : Any> HeaderParam<T>.optional(): HeaderParam<T?> =
    HeaderParam<T?>(name, codec, required = false, default = null, description = description)

fun <T : Any> HeaderParam<T>.default(value: T): HeaderParam<T> =
    HeaderParam(name, codec, required = false, default = value, description = description)

@Suppress("UNCHECKED_CAST")
fun <T : Any> CookieParam<T>.optional(): CookieParam<T?> =
    CookieParam<T?>(name, codec, required = false, default = null, description = description)

fun <T : Any> CookieParam<T>.default(value: T): CookieParam<T> =
    CookieParam(name, codec, required = false, default = value, description = description)

inline fun <reified T> jsonBody(description: String? = null): JsonBody<T> =
    JsonBody(typeOf<T>(), description)

/**
 * An `application/x-www-form-urlencoded` body decoded into [T].
 *
 * ```
 * data class SignIn(val user: String, val remember: Boolean)
 *
 * val credentials = formBody<SignIn>(description = "The sign-in form")
 * ```
 */
inline fun <reified T> formBody(description: String? = null): FormBody<T> =
    FormBody(typeOf<T>(), description)

fun rawBody(description: String? = null): RawBody = RawBody(description)

/**
 * A named text field of a multipart body. Takes the same codecs and the same
 * [optional]/[default] modifiers a query parameter takes, because it is the
 * same kind of thing: one string on the wire, decoded into a declared type.
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
 * A named file field of a multipart body. The handler gets an [UploadedFile]
 * and reads it as a stream.
 *
 * [contentType] is what the part is expected to carry — `image/png`, or a
 * comma-separated list, or a wildcard. It reaches the document's `encoding`
 * block, which is what tells a browser and Swagger UI what to offer; nothing
 * here rejects a part that carries something else, for the same reason nothing
 * here validates a token.
 */
fun filePart(
    name: String,
    contentType: String? = null,
    description: String? = null,
): FilePart<UploadedFile> = FilePart(name, required = true, contentType = contentType, description = description)

/** Makes the file optional; reading it yields `null` when the caller sent no such part. */
fun FilePart<UploadedFile>.optional(): FilePart<UploadedFile?> =
    FilePart(name, required = false, contentType = contentType, description = description)
