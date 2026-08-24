package dev.pelican.jsoniter

import com.jsoniter.JsonIterator
import com.jsoniter.output.JsonStream
import com.jsoniter.spi.Config
import com.jsoniter.spi.TypeLiteral
import dev.pelican.BodyCodec
import dev.pelican.Codecs
import dev.pelican.JsonObj
import dev.pelican.SchemaComponents
import kotlin.reflect.KType

/**
 * Reads and writes bodies with jsoniter, and derives schemas from the same
 * constructors the binding reads.
 *
 * ```
 * Api(routes, codecs = JsoniterCodecs)                      // defaults
 * Api(routes, codecs = JsoniterCodecs(jsoniterConfig { … })) // configured
 * ```
 *
 * jsoniter is the fastest of the three and the only one that predates Kotlin
 * entirely, which is the whole shape of this module: the parsing and the
 * printing are jsoniter's, and the part that knows a data class from a bean is
 * [KotlinBinding][jsoniterConfig]'s. Payload types need no annotations and no
 * compiler plugin — a `data class` is enough — at the cost of the reflection
 * this does at startup, once per type.
 *
 * The library has been unmaintained since 2018. That is worth knowing before
 * choosing it over `pelican-jackson`, and it is also why the binding sits here
 * rather than in a fork: nothing above needs jsoniter to change.
 *
 * What it does not do is polymorphism *outside* a sealed hierarchy. A sealed
 * class travels with a `type` discriminator, described and read; an open one
 * would need a registry of implementations, which jsoniter has no notion of.
 */
class JsoniterCodecs(private val config: Config) : Codecs {

    init {
        // A plain `Config` parses perfectly well and would bind a data class
        // wrongly rather than loudly — a missing property with a default
        // silently arriving as zero. Better refused at assembly.
        require(config is JsoniterConfig) {
            "JsoniterCodecs needs a config from jsoniterConfig { }, which is what teaches jsoniter about Kotlin"
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> codec(type: KType): BodyCodec<T> {
        // Resolved once, when the Api is assembled, like every other codec.
        // jsoniter keys its own decoders off this, and a `TypeLiteral` is what
        // carries `List<Order>` rather than the erased `ArrayList` a value's
        // own class would report.
        val literal = TypeLiteral.create(type.toJsoniterType())
        return object : BodyCodec<T> {
            override fun encodeToString(value: T): String = JsonStream.serialize(config, literal, value)

            override fun decodeFromString(text: String): T =
                JsonIterator.deserialize(config, text.toByteArray(), literal) as T
        }
    }

    override fun schema(type: KType, components: SchemaComponents): JsonObj =
        ReflectionSchemas(components).schemaFor(type)

    companion object Default : Codecs by JsoniterCodecs(jsoniterConfig())
}
