package io.github.matthewjones372.pelican.kotlinx

import io.github.matthewjones372.pelican.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
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
 * The `Json` used when none is supplied, configured to read what the schema
 * this module publishes says a caller may send.
 */
fun defaultJson(): Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // A nullable property with no default is not `required` in the schema, and
    // without this kotlinx.serialization throws MissingFieldException when one
    // is absent — a 400 for a payload the document, Jackson and jsoniter all
    // accept. The flag governs writing too, so this module leaves a null out
    // where the other two spell it; both satisfy the schema, and only one of
    // the two directions was ever a refusal.
    explicitNulls = false
}
