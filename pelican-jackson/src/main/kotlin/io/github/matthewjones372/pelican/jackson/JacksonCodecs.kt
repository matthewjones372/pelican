package io.github.matthewjones372.pelican.jackson

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonMapperBuilder
import io.github.matthewjones372.pelican.*
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.media.Schema
import kotlin.reflect.KType
import kotlin.reflect.jvm.javaType
import io.swagger.v3.core.util.Json31 as SwaggerJson31

/**
 * Reads and writes bodies with Jackson, and describes types with swagger-core.
 * Use the object for the defaults, or construct one with your own mapper.
 *
 * swagger-core rather than a walk over Kotlin reflection because it reads
 * Jackson's own annotations, so `@JsonProperty`, `@JsonIgnore` and
 * `@JsonTypeInfo` shape the document without being declared twice.
 */
class JacksonCodecs(private val mapper: ObjectMapper) : Codecs {

    /**
     * swagger-core introspects with its own mapper, which knows nothing about
     * Kotlin, so a resolver backed by [mapper] goes at the front of the chain.
     */
    private val describer = KotlinAwareModelResolver(mapper).apply { openapi31(true) }

    /**
     * The chain a type is resolved through, with [describer] at the front. The
     * resolver is a value of its own because it is also read directly: it knows
     * which class each component came from, which the union rewrite needs.
     */
    private val converters = ModelConverters().apply { addConverter(describer) }

    @Suppress("UNCHECKED_CAST")
    override fun <T> codec(type: KType): BodyCodec<T> {
        // Once, when the Api is assembled: KType -> JavaType is reflection.
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

        // Every model swagger touched becomes a component, so a shared type is
        // written down once. Rewritten on the way, because a sealed hierarchy
        // is the one shape swagger-core describes lossily — see
        // `unionsRewritten`, which runs over the whole batch since a hierarchy
        // is a parent and its branches together.
        val described = resolved?.referencedSchemas.orEmpty().mapValues { (_, schema) -> schema.toJsonObj() }
        unionsRewritten(described, describer.described).forEach { (name, schema) ->
            if (!components.isRegistered(name)) components.register(name, schema)
        }

        val root = resolved?.schema ?: return jsonObj { "type" to "object" }
        val json = root.toJsonObj()

        // swagger-core resolved this from an erased Java type, so a nullable
        // element inside a `List` or `Map` is gone and only [type] still knows.
        // Nullability inside a model is `KotlinAwareModelResolver`'s problem.
        return json.withNullabilityOf(type)
    }

    companion object Default : Codecs by JacksonCodecs(defaultMapper())
}

/**
 * The mapper used when none is supplied: Kotlin- and `java.time`-aware, lenient
 * about unknown fields, and writing dates as strings.
 */
fun defaultMapper(): ObjectMapper = jacksonMapperBuilder()
    .addModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    // A null field is written rather than dropped: absent and null mean
    // different things where the document says the field is nullable.
    .defaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS))
    .build()

// ------------------------------------------------- swagger Schema -> JsonObj

/**
 * `Json31` and not `Json`: the serializer decides the dialect. The 3.0 mapper
 * emits `nullable` and a scalar `type`; this one emits a `type` array, which is
 * the 3.1 spelling — so a half-converted pipeline loses nullability silently.
 */
private fun Schema<*>.toJsonObj(): JsonObj =
    SwaggerJson31.mapper().convertValue(this, JsonNode::class.java).toJsonValue() as? JsonObj
        ?: jsonObj { "type" to "object" }

/** An artefact of swagger's object model, not part of the schema. */
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
