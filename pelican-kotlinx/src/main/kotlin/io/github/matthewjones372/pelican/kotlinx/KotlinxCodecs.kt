package io.github.matthewjones372.pelican.kotlinx

import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.BodyDecodeFailure
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonBool
import io.github.matthewjones372.pelican.JsonNull
import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.SchemaComponents
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.serializer
import java.io.ByteArrayInputStream
import kotlin.reflect.KType
import kotlinx.serialization.json.JsonNull as KotlinxNull

/**
 * Reads and writes bodies with kotlinx.serialization, deriving schemas from the
 * same `SerialDescriptor`s the serializers are built from.
 *
 * Payload types must be `@Serializable`, which is the trade for needing no
 * runtime reflection.
 */
class KotlinxCodecs(private val json: Json) : Codecs {

    init { refuseWhatTheSchemaCannotSay(json) }

    @Suppress("UNCHECKED_CAST")
    override fun <T> codec(type: KType): BodyCodec<T> {
        // Resolved once, when the Api is assembled, like every other codec.
        val serializer = serializer(type) as KSerializer<T>
        return object : BodyCodec<T> {
            override fun encodeToString(value: T): String = json.encodeToString(serializer, value)
            override fun decodeFromString(text: String): T = json.decodeFromString(serializer, text)

            /** kotlinx's UTF-8 stream reader on the bytes as they arrived, rather than a String copy of them. */
            @OptIn(ExperimentalSerializationApi::class)
            override fun decodeFrom(bytes: ByteArray): T =
                json.decodeFromStream(serializer, ByteArrayInputStream(bytes))
        }
    }

    /** Parsed by the configured [Json], held to the depth and number limits Jackson's `readTree` has by default. */
    override fun readTree(text: String): JsonValue =
        try {
            requireDepthWithin(text, MAX_DEPTH)
            json.parseToJsonElement(text).toJsonValue()
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, as is JsonNum refusing a non-finite number.
            throw BodyDecodeFailure(e.message ?: "The text is not JSON", e)
        }

    override fun schema(type: KType, components: SchemaComponents): JsonObj =
        DescriptorSchemas(components, json.configuration.classDiscriminator)
            .schemaFor(serializer(type).descriptor)

    companion object Default : Codecs by KotlinxCodecs(defaultJson())
}

/**
 * Measured before kotlinx parses, because its tree reader recurses on arrays
 * with no limit and a deep enough one is a `StackOverflowError`, the failure
 * spec 0045 exists to keep off the network path.
 */
private fun requireDepthWithin(text: String, limit: Int) {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        when {
            escaped -> escaped = false
            inString && c == '\\' -> escaped = true
            c == '"' -> inString = !inString
            inString -> Unit
            c == '[' || c == '{' -> require(++depth <= limit) { "The document nests deeper than $limit" }
            c == ']' || c == '}' -> depth--
        }
    }
}

private fun JsonElement.toJsonValue(): JsonValue = when (this) {
    is JsonObject -> JsonObj(mapValues { (_, value) -> value.toJsonValue() })
    is JsonArray -> JsonArr(map { it.toJsonValue() })
    is JsonPrimitive -> toJsonValue()
}

/** kotlinx keeps a number as its digits, and a `BigInteger` from a million of them is CPU worth refusing. */
private fun JsonPrimitive.toJsonValue(): JsonValue {
    if (isString) return JsonStr(content)
    if (this == KotlinxNull) return JsonNull
    booleanOrNull?.let { return JsonBool(it) }
    require(content.length <= MAX_NUMBER_LENGTH) { "A number is longer than $MAX_NUMBER_LENGTH characters" }
    // Whole numbers stay whole, past Long too, as core's own reader keeps them.
    return JsonNum(content.toLongOrNull() ?: content.toBigIntegerOrNull() ?: content.toDouble())
}

/** Jackson's `StreamReadConstraints` defaults, which is what `JacksonCodecs.readTree` is held to. */
private const val MAX_DEPTH = 1000
private const val MAX_NUMBER_LENGTH = 1000

/**
 * The `Json` used when none is supplied, configured to behave as the Jackson
 * and jsoniter defaults do, so switching codecs changes the library and not
 * the wire.
 */
fun defaultJson(): Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // A nullable property with no default is not `required` in the schema, and
    // without this kotlinx.serialization throws MissingFieldException when one
    // is absent — a 400 for a payload the document says is valid. The flag
    // governs writing too: a null property is left out, and `defaultMapper()`
    // and jsoniter's ObjectEncoder leave it out to match.
    explicitNulls = false
}

/**
 * The four settings the schema derivation reads. Everything else a `Json` can
 * be told is invisible to a `SerialDescriptor`, so honouring it would change
 * the bytes and leave the document saying what it said before.
 */
private val readByTheSchema = setOf("classDiscriminator", "ignoreUnknownKeys", "encodeDefaults", "explicitNulls")

/**
 * Every setting, so a version of kotlinx.serialization that adds one adds it
 * here too rather than having it silently ignored.
 */
@OptIn(ExperimentalSerializationApi::class)
private val settings: List<Pair<String, (JsonConfiguration) -> Any?>> = listOf(
    "encodeDefaults" to { it.encodeDefaults },
    "ignoreUnknownKeys" to { it.ignoreUnknownKeys },
    "isLenient" to { it.isLenient },
    "allowStructuredMapKeys" to { it.allowStructuredMapKeys },
    "prettyPrint" to { it.prettyPrint },
    "explicitNulls" to { it.explicitNulls },
    "prettyPrintIndent" to { it.prettyPrintIndent },
    "coerceInputValues" to { it.coerceInputValues },
    "useArrayPolymorphism" to { it.useArrayPolymorphism },
    "classDiscriminator" to { it.classDiscriminator },
    "allowSpecialFloatingPointValues" to { it.allowSpecialFloatingPointValues },
    "useAlternativeNames" to { it.useAlternativeNames },
    "namingStrategy" to { it.namingStrategy },
    "decodeEnumsCaseInsensitive" to { it.decodeEnumsCaseInsensitive },
    "allowTrailingComma" to { it.allowTrailingComma },
    "allowComments" to { it.allowComments },
    "classDiscriminatorMode" to { it.classDiscriminatorMode },
)

/**
 * Refused at construction rather than translated by half: a `SnakeCase`
 * naming strategy writes `placed_at` while the schema this module derives from
 * the descriptor still publishes `placedAt`, and a caller reading the document
 * is refused by the service that published it.
 *
 * What "set" means is measured against kotlinx.serialization's own defaults, so
 * a `Json` that names a setting and leaves it as it was is not refused for it.
 */
private fun refuseWhatTheSchemaCannotSay(json: Json) {
    val defaults = Json.Default.configuration
    val changed = settings
        .filterNot { (name, _) -> name in readByTheSchema }
        .filter { (_, read) -> read(json.configuration) != read(defaults) }
        .map { (name, _) -> name }

    require(changed.isEmpty()) {
        "KotlinxCodecs derives its schemas from kotlinx.serialization's descriptors, which say nothing " +
            "about ${changed.joinToString()}. Honouring ${if (changed.size == 1) "it" else "them"} would " +
            "change the bytes and leave the document describing the bytes before. This module reads " +
            "${readByTheSchema.joinToString()}; leave the rest as kotlinx.serialization has them, or " +
            "describe the payloads the way they are written."
    }
}
