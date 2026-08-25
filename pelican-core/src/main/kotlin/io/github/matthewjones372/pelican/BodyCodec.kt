package io.github.matthewjones372.pelican

import kotlin.reflect.KType

/**
 * Reads and writes a request or response body. Descriptions hold a [KType] and
 * never one of these, which is why swapping JSON libraries changes one line.
 */
interface BodyCodec<T> {
    fun encodeToString(value: T): String
    fun decodeFromString(text: String): T
}

/**
 * Thrown when a request body cannot be decoded. Backends wrap whatever the
 * codec threw, so mapping a bad body to a 400 names no JSON library.
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
 * Describes a type as an OpenAPI schema. Separate from [CodecFactory] because
 * documentation has to be generatable without a server.
 */
interface SchemaSource {
    fun schema(type: KType, components: SchemaComponents): JsonObj
}

/**
 * The same schema with null allowed, spelled as OpenAPI 3.1 does: the dialect
 * is JSON Schema 2020-12, so a nullable string is `type: ["string", "null"]`.
 *
 * A `$ref` is why this is a function rather than a line in each schema source.
 * `type` beside a `$ref` means "and also of this type", so a reference goes
 * under `anyOf` next to a bare null schema.
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
 * The same schema, agreeing with [type] about null at every depth.
 *
 * `List<Order?>` is the shape that needs it: erasure takes the element's
 * nullability, so a source deriving types from Java reflection cannot tell it
 * from `List<Order>`. Only the Kotlin type knows, so the two are walked
 * together — `items` and `additionalProperties` against the last type argument,
 * which is the element for `List`, `Array` and `Map` alike.
 *
 * A source already tracking nullability all the way down, as
 * kotlinx.serialization's descriptors do, should not call this over its work.
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
 * How a request body is read, once the request says what it is: one entry per
 * media type the endpoint declared. The choosing and the 415 live here so that
 * three backends cannot answer a `Content-Type` three ways.
 */
class RequestBodyCodecs internal constructor(private val byMediaType: Map<String, BodyCodec<Any?>>) {

    /**
     * The body as the value it decodes to.
     *
     * [contentType] is consulted only where the endpoint declared a choice.
     * With one encoding the header carries no information — a `jsonBody<T>()`
     * sent without one still decodes, and a body that is not JSON is a 400
     * describing the actual problem. With alternatives it is the only thing
     * saying which decode was meant, so an undeclared type is a 415.
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
 * Which codec reads this request body, or null for the ones no codec reads — a
 * raw stream, a multipart envelope, no body. A decision about descriptions
 * rather than about a server, so it is here and not in each interpreter.
 */
fun Codecs.requestBodyCodec(input: BodyInput<*>?): RequestBodyCodecs? = when (input) {
    is JsonBody<*>, is FormBody<*> -> RequestBodyCodecs(mapOf(input.mediaType to oneBodyCodec(input)))

    // Resolved here rather than on the request that picks one, so an unreadable
    // encoding is a startup failure and not a 500 for whoever chose it.
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
