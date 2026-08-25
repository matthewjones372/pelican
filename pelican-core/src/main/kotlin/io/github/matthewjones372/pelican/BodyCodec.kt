package io.github.matthewjones372.pelican

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
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

/**
 * Which type each component name is being described from, and the refusal when
 * two want one name.
 *
 * All three schema sources name a component after the type's simple name, and
 * all three registered the second silently — so a document said `Item` once and
 * a consumer decoded the wrong one. Here rather than three times over, so the
 * three cannot differ about what a collision is or what to do about it.
 */
class SchemaNames {
    private val describedBy = mutableMapOf<String, String>()

    /** Records that [owner] is described as [name]. Refuses where something else already is. */
    fun claim(name: String, owner: String) {
        val already = describedBy.put(name, owner)
        require(already == null || already == owner) {
            "Two types are described as '$name': $already and $owner. A schema component is named " +
                "after the type, and a document holds one '$name', so the second would take the " +
                "first's schema and every reader of the document would believe it. Rename one, or " +
                "keep them out of the same document."
        }
    }
}

/** Default [SchemaComponents] implementation, used by the OpenAPI interpreter. */
class SchemaRegistry : SchemaComponents {
    private val components = LinkedHashMap<String, JsonObj>()

    override fun register(name: String, schema: JsonObj) { components[name] = schema }
    override fun isRegistered(name: String) = name in components
    override fun ref(name: String) = jsonObj { "\$ref" to "#/components/schemas/$name" }

    fun all(): JsonObj = JsonObj(components.toMap())
}
