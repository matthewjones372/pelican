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

/** The default when none is configured: fails with an actionable message. */
object NoCodecs : Codecs {
    private fun fail(): Nothing = error(
        "No codecs configured. Pass `codecs = JacksonCodecs` to api(...) " +
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
