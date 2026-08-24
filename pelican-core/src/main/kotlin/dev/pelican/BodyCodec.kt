package dev.pelican

import kotlin.reflect.KType

/**
 * Reads and writes a request or response body.
 *
 * Descriptions never hold one of these — they hold a [KType] and nothing more.
 * The codec is resolved when an [Api] is assembled, which is why swapping
 * Jackson for kotlinx.serialization changes one line in one file and touches
 * no endpoint description.
 */
interface BodyCodec<T> {
    fun encodeToString(value: T): String
    fun decodeFromString(text: String): T
}

/**
 * Thrown when a request body cannot be decoded.
 *
 * Backends wrap whatever their codec threw in this, so mapping a bad body to a
 * 400 does not require naming `SerializationException` or `JacksonException` —
 * which a backend module has no business knowing about.
 */
class BodyDecodeFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Resolves a codec for a type. Implemented by `pelican-jackson` and `pelican-kotlinx`. */
interface CodecFactory {
    fun <T> codec(type: KType): BodyCodec<T>
}

/**
 * Where named schemas accumulate while a document is being built. Schema
 * sources register component definitions here and return a `$ref` to them.
 */
interface SchemaComponents {
    fun register(name: String, schema: JsonObj)
    fun isRegistered(name: String): Boolean
    fun ref(name: String): JsonObj
}

/**
 * Describes a type as an OpenAPI schema. Kept separate from [CodecFactory]
 * because documentation must be generatable without a server — and, in
 * practice, both implementations derive schemas from the same metadata their
 * codec uses, so the two stay consistent.
 */
interface SchemaSource {
    fun schema(type: KType, components: SchemaComponents): JsonObj
}

/**
 * The same schema, but null is also allowed — spelled the way OpenAPI 3.1 does.
 *
 * 3.0 had a keyword for this, `nullable: true`, sitting outside JSON Schema and
 * meaning nothing to a JSON Schema validator. 3.1 dropped it: the dialect is
 * JSON Schema 2020-12, where null is a type like any other, so a nullable
 * string is `type: ["string", "null"]`.
 *
 * A `$ref` is the awkward one, and it is why this is a function rather than a
 * line in each schema source. `type` beside a `$ref` says "and also of this
 * type", not "or null", so a reference has to be put under `anyOf` next to a
 * bare null schema. That case is also the one 3.0 could not express at all —
 * a `$ref` took no siblings there, so both schema sources simply dropped the
 * nullability and documented a field that may be null as if it never were.
 *
 * It lives in core, beside [SchemaSource], because two schema sources that
 * spell this differently produce two different documents from one set of
 * descriptions — which is the thing this library exists not to do.
 */
fun JsonObj.orNull(): JsonObj = when (val type = this["type"]) {
    is JsonStr -> this + jsonObj { put("type", jsonArr(listOf(type, JsonStr("null")))) }

    is JsonArr ->
        if (JsonStr("null") in type.items) this
        else this + jsonObj { put("type", JsonArr(type.items + JsonStr("null"))) }

    // No `type` to widen: a `$ref`, or a schema built out of `allOf`/`anyOf`.
    else -> jsonObj {
        put("anyOf", jsonArr(listOf(this@orNull, jsonObj { "type" to "null" })))
    }
}

/**
 * The same schema, made to agree with [type] about null at every depth [type]
 * has — not only at the top.
 *
 * `List<Order?>` is the shape that makes this worth a function. Erasure takes
 * the element's nullability with it, so a schema source deriving types from
 * Java reflection sees `List<Order?>` and `List<Order>` as the same thing and
 * describes the first one wrongly. Only the Kotlin type still knows, so the
 * two are walked together: `items` against the element type,
 * `additionalProperties` against the value type, as deep as the generics go.
 *
 * The element is the *last* type argument, which is right for every shape a
 * schema spells this way: `List<T>` and `Array<T>` have one argument, `Map<K,
 * V>` has two and it is `V` that becomes the schema. A star projection carries
 * no type, and then there is nothing to descend into.
 *
 * A source whose own metadata already tracks nullability all the way down —
 * kotlinx.serialization's descriptors do — has no need of this and should not
 * call it twice over its own work.
 */
fun JsonObj.withNullabilityOf(type: KType): JsonObj {
    val element = type.arguments.lastOrNull()?.type
    var schema = this
    if (element != null) {
        for (key in listOf("items", "additionalProperties")) {
            val child = schema[key] as? JsonObj ?: continue
            schema += jsonObj { put(key, child.withNullabilityOf(element)) }
        }
    }
    return if (type.isMarkedNullable) schema.orNull() else schema
}

/** A [CodecFactory] that also knows how to describe its types. */
interface Codecs : CodecFactory, SchemaSource

/**
 * How a request body is read, once the request says what it is.
 *
 * One entry per media type the endpoint declared, which is one entry for almost
 * every endpoint there has ever been. The choosing and the 415 live here rather
 * than in each interpreter for the same reason the multipart parser does: three
 * copies of "which codec reads this" would be three chances for one backend to
 * answer a `Content-Type` differently from the other two.
 */
class RequestBodyCodecs internal constructor(private val byMediaType: Map<String, BodyCodec<Any?>>) {

    /**
     * The body as the value it decodes to.
     *
     * [contentType] is consulted **only where the endpoint declared a choice.**
     * With one encoding there is nothing to choose, and an endpoint that started
     * refusing a request whose `Content-Type` it never checked before would be
     * breaking callers over a header that carries no information here — a
     * `jsonBody<T>()` sent with no header at all has always been decoded, and
     * whatever the codec makes of a body that is not JSON is a 400 that
     * describes the actual problem. Where there *are* alternatives the header is
     * the only thing that says which decode was meant, so a media type nobody
     * declared is a 415 rather than a guess.
     *
     * Whatever the codec throws is its own library's exception; wrapping it in
     * core's own failure is what lets an interpreter map a bad body to a 400
     * without naming Jackson or kotlinx.serialization.
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
        return byMediaType[declared] ?: throw ApiException(
            415,
            "Unsupported media type",
            "The request body arrived as ${declared ?: "no media type at all"}, and this endpoint " +
                "reads ${byMediaType.keys.joinToString(" or ")}. Send one of those in Content-Type.",
        )
    }
}

/**
 * Which codec reads this request body, or null for the bodies no codec reads —
 * a raw stream, a multipart envelope, no body at all.
 *
 * Lives here rather than in each interpreter because "a form body goes through
 * the configured codec, having been shaped by the published schema first" is a
 * decision about descriptions, not about a server. Three copies of it would be
 * three chances for one backend to read a form differently from the other two.
 * Called once per endpoint when a route is built, like every other codec.
 */
fun Codecs.requestBodyCodec(input: BodyInput<*>?): RequestBodyCodecs? = when (input) {
    is JsonBody<*>, is FormBody<*> -> RequestBodyCodecs(mapOf(input.mediaType to oneBodyCodec(input)))

    // Every alternative is resolved here, not on the request that picks one, so
    // an endpoint offering an encoding its payload type cannot be read from is a
    // startup failure rather than a 500 for whichever caller chose that one.
    is NegotiatedBody<*> -> RequestBodyCodecs(
        input.alternatives.associate { it.mediaType to oneBodyCodec(it) },
    )

    null, is RawBody, is MultipartBody -> null
}

/** What reads a body of one media type. Every alternative is one of these. */
private fun Codecs.oneBodyCodec(input: BodyInput<*>): BodyCodec<Any?> = when (input) {
    is JsonBody<*> -> codec(input.type)
    is FormBody<*> -> formCodec(input.type)
    else -> error("$input is not a body a codec reads")
}

/** The default when none is configured: fails with an actionable message. */
object NoCodecs : Codecs {
    private fun fail(): Nothing = error(
        "No codecs configured. Pass `codecs = JacksonCodecs` to Api(...) " +
            "(pelican-jackson) or `codecs = KotlinxCodecs` (pelican-kotlinx).",
    )

    override fun <T> codec(type: KType): BodyCodec<T> = fail()
    override fun schema(type: KType, components: SchemaComponents): JsonObj = fail()
}

/** Default [SchemaComponents] implementation, used by the OpenAPI interpreter. */
class SchemaRegistry : SchemaComponents {
    private val components = LinkedHashMap<String, JsonObj>()

    override fun register(name: String, schema: JsonObj) { components[name] = schema }
    override fun isRegistered(name: String) = name in components
    override fun ref(name: String) = jsonObj { "\$ref" to "#/components/schemas/$name" }

    fun all(): JsonObj = JsonObj(components.toMap())
}
