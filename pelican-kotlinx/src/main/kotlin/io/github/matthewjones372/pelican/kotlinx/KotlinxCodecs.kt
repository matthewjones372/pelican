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
 * Not a fallback: the second implementation is what makes the pluggability
 * testable, and `CodecAgreementTest` compares the two documents.
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
 * The `Json` used when none is supplied, configured to behave as the Jackson
 * default does, so switching codecs changes the library and not the wire.
 */
fun defaultJson(): Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
