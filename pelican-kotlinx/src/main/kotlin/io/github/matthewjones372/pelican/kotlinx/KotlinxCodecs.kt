package io.github.matthewjones372.pelican.kotlinx

import io.github.matthewjones372.pelican.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.serializer
import kotlin.reflect.KType

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
        }
    }

    override fun schema(type: KType, components: SchemaComponents): JsonObj =
        DescriptorSchemas(components, json.configuration.classDiscriminator)
            .schemaFor(serializer(type).descriptor)

    companion object Default : Codecs by KotlinxCodecs(defaultJson())
}

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
