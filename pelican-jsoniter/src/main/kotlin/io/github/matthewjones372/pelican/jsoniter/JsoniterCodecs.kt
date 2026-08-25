package io.github.matthewjones372.pelican.jsoniter

import com.jsoniter.JsonIterator
import com.jsoniter.output.JsonStream
import com.jsoniter.spi.Config
import com.jsoniter.spi.TypeLiteral
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
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
 * jsoniter's parser and printer are the fastest of the three, and jsoniter is
 * the only one of the three that predates Kotlin entirely, which is the shape
 * of this module: the parsing and the printing are its, and the part that knows
 * a data class from a bean is [jsoniterConfig]'s. Payload types need no
 * annotations and no compiler plugin — a `data class` is enough — paid for by
 * the reflection the binding does once per type, and by a `callBy` on every
 * value read.
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
