package dev.pelican.jackson

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.pelican.*
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.media.Schema
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType
import io.swagger.v3.core.util.Json31 as SwaggerJson31

/**
 * Reads and writes bodies with Jackson, and describes types with swagger-core.
 *
 * Use it as an object when the defaults are fine, or construct one to supply
 * your own mapper:
 *
 * ```
 * Api(routes, codecs = JacksonCodecs)                 // defaults
 * Api(routes, codecs = JacksonCodecs(myObjectMapper)) // configured
 * ```
 *
 * Schemas deliberately come from swagger-core rather than a hand-rolled walker
 * over Kotlin reflection: it reads Jackson's own annotations, so it sees
 * exactly what Jackson sees. `@JsonProperty`, `@JsonIgnore` and `@JsonTypeInfo`
 * shape the document without being told about them twice.
 */
class JacksonCodecs(private val mapper: ObjectMapper) : Codecs {

    /**
     * swagger-core's own converter introspects with its own mapper, which knows
     * nothing about Kotlin. Putting a resolver backed by [mapper] at the front
     * of the chain is what makes data-class defaults and nullability visible to
     * the schema generator as well as to the parser.
     *
     * `openapi31(true)` is how swagger-core is asked for 3.1, and it is asked on
     * the resolver rather than on [ModelConverters] because the flag there only
     * configures the resolver that class builds for itself — which this one
     * stands in front of, so it would never be reached. Asked this way, models
     * come back as `JsonSchema` with a `types` *set*, which is what lets a
     * nullable property become a type union at all.
     */
    private val converters = ModelConverters().apply {
        addConverter(KotlinAwareModelResolver(mapper).openapi31(true))
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T> codec(type: KType): BodyCodec<T> {
        // Resolved once, when the Api is assembled. KType -> JavaType goes
        // through reflection and is not cheap enough to do per request.
        val javaType = mapper.constructType(type.javaType)
        return object : BodyCodec<T> {
            override fun encodeToString(value: T): String = mapper.writeValueAsString(value)
            override fun decodeFromString(text: String): T = mapper.readValue(text, javaType)
        }
    }

    override fun schema(type: KType, components: SchemaComponents): JsonObj {
        val resolved = converters.resolveAsResolvedSchema(
            AnnotatedType(type.javaType).resolveAsRef(true),
        )

        // Every model swagger touched becomes a component, so a type used by
        // ten endpoints is written down once and referenced ten times.
        resolved?.referencedSchemas?.forEach { (name, schema) ->
            if (!components.isRegistered(name)) components.register(name, schema.toJsonObj())
        }

        val root = resolved?.schema ?: return jsonObj { "type" to "object" }
        val json = root.toJsonObj()

        // Core's own walk, not a spelling of ours. swagger-core resolved this
        // from an erased Java type, so a nullable element inside a `List` or a
        // `Map` is gone by the time it gets here and only [type] still knows.
        // Nullability *inside a model* is a different problem with a different
        // owner — see `KotlinAwareModelResolver`, which has the constructor.
        return json.withNullabilityOf(type)
    }

    companion object Default : Codecs by JacksonCodecs(defaultMapper())
}

/**
 * The mapper used when none is supplied: Kotlin-aware, `java.time`-aware,
 * lenient about unknown fields, and writing dates as strings rather than
 * epoch numbers.
 */
fun defaultMapper(): ObjectMapper = jacksonObjectMapper().apply {
    registerModule(JavaTimeModule())
    disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    setSerializationInclusion(JsonInclude.Include.ALWAYS)
}

// ------------------------------------------------- swagger Schema -> JsonObj

/**
 * `Json31` and not `Json`, because the dialect is decided by the serializer.
 * The same model object written through the 3.0 mapper emits `nullable` and a
 * scalar `type`; through this one it emits a `type` array and drops `nullable`
 * on the floor, which is exactly the 3.1 spelling and exactly the reason a
 * half-converted pipeline would silently lose nullability rather than fail.
 */
private fun Schema<*>.toJsonObj(): JsonObj =
    SwaggerJson31.mapper().convertValue(this, JsonNode::class.java).toJsonValue() as? JsonObj
        ?: jsonObj { "type" to "object" }

/**
 * swagger's model classes carry a bookkeeping field that records whether an
 * example was set explicitly. It is an artefact of their object model, not part
 * of the schema, and it is not wanted in the document.
 */
private const val SWAGGER_BOOKKEEPING = "exampleSetFlag"

private fun JsonNode.toJsonValue(): JsonValue = when {
    isObject -> JsonObj(
        properties()
            .filter { (name, _) -> name != SWAGGER_BOOKKEEPING }
            .associate { (name, value) -> name to value.toJsonValue() },
    )

    isArray -> JsonArr(map { it.toJsonValue() })

    isTextual -> JsonStr(textValue())

    isBoolean -> JsonBool(booleanValue())

    isIntegralNumber -> JsonNum(longValue())

    isNumber -> JsonNum(doubleValue())

    else -> JsonNull
}
