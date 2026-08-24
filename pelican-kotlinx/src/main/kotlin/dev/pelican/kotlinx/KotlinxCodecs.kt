package dev.pelican.kotlinx

import dev.pelican.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KType

/**
 * Reads and writes bodies with kotlinx.serialization, and derives schemas by
 * walking the same `SerialDescriptor`s the serializers are built from.
 *
 * ```
 * Api(routes, codecs = KotlinxCodecs)          // defaults
 * Api(routes, codecs = KotlinxCodecs(myJson))  // configured
 * ```
 *
 * This module is not a fallback — it is the second implementation that makes
 * the pluggability testable. `CodecAgreementTest` generates the same spec
 * through this and through `JacksonCodecs` and compares the results.
 *
 * Payload types must be `@Serializable`, which is the trade for not needing
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
 * The `Json` used when none is supplied. Configured to behave the way the
 * Jackson default does — lenient about unknown fields, and writing defaulted
 * properties rather than omitting them — so switching codecs changes the
 * library and not the wire format.
 */
fun defaultJson(): Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
