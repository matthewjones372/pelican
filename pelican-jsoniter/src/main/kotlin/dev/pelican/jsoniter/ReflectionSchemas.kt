package dev.pelican.jsoniter

import dev.pelican.JsonObj
import dev.pelican.JsonStr
import dev.pelican.SchemaComponents
import dev.pelican.jsonArr
import dev.pelican.jsonObj
import dev.pelican.jsonStrings
import dev.pelican.orNull
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor

/**
 * Turns Kotlin types into OpenAPI schemas, by reflection.
 *
 * The other two codec modules each describe a type with metadata their library
 * already keeps — swagger-core reads Jackson's annotations, and kotlinx's
 * descriptors are the serializers' own. jsoniter keeps none: it has no schema
 * model, no annotations of its own worth reading, and no view of a type beyond
 * the fields it is about to write. So the description comes from where the
 * binding comes from — the primary constructor — which is the same metadata
 * `KotlinBinding` reads, and that is what keeps this document and this module's
 * wire format from drifting apart.
 *
 * Named object types are hoisted into `#/components/schemas` and referenced;
 * primitives, lists, maps and enums are inlined. That is the shape both other
 * modules produce, which is what lets the documents be compared.
 */
internal class ReflectionSchemas(private val components: SchemaComponents) {

    /** Names currently being built, so a recursive type terminates at its ref. */
    private val inProgress = mutableSetOf<String>()

    fun schemaFor(type: KType): JsonObj {
        val base = build(type)
        // `orNull` rather than a spelling written here, so this and the other
        // two schema sources cannot drift into two spellings of one fact.
        return if (type.isMarkedNullable) base.orNull() else base
    }

    private fun build(type: KType): JsonObj {
        val kclass = type.classifier as? KClass<*> ?: return objectSchema
        primitives[kclass]?.let { return it }
        return when {
            isScalar(kclass) -> jsonObj {
                "type" to "string"
                putIfNotNull("format", stringFormats[kclass])
            }

            // Inlined rather than hoisted, which is what swagger-core does for
            // the Jackson side. An enum is small enough that a shared definition
            // buys little, and agreeing is worth more than saving the bytes.
            kclass.java.isEnum -> jsonObj {
                "type" to "string"
                put("enum", jsonStrings(kclass.java.enumConstants.filterIsInstance<Enum<*>>().map { it.name }))
            }

            isCollection(kclass) -> jsonObj {
                "type" to "array"
                put("items", elementAt(type, 0))
            }

            kclass.isSubclassOf(Map::class) -> jsonObj {
                "type" to "object"
                put("additionalProperties", elementAt(type, 1))
            }

            kclass.isSealed -> named(kclass) { union(kclass) }

            // Refused rather than described as the object it is not. A `Page<T>`
            // would document one shape for every argument it is ever used with,
            // and this module could not read the payload back anyway — see
            // `KotlinBinding`, which refuses the same shape for the same reason.
            kclass.typeParameters.isNotEmpty() && kclass.primaryConstructor != null ->
                error(
                    "pelican-jsoniter cannot describe ${kclass.simpleName}<>: a type argument reaches neither " +
                        "the schema nor the binding. Use a payload type without type parameters, or " +
                        "JacksonCodecs or KotlinxCodecs.",
                )

            kclass.objectInstance != null -> objectSchema

            kclass.primaryConstructor != null -> named(kclass) { classSchema(kclass) }

            // A type with no constructor to read and no library metadata to fall
            // back on: `Any`, an interface, a Java class written for beans.
            // "An object" is all that is honestly known about it.
            else -> objectSchema
        }
    }

    /**
     * A sealed hierarchy as the union it is: `oneOf` over the branches, with the
     * `discriminator` naming the property that tells them apart and the value
     * that picks each one — the same property `KotlinBinding` writes, and the
     * same shape `pelican-import` reads back.
     */
    private fun union(kclass: KClass<*>): JsonObj {
        val branches = kclass.leaves().map { it.discriminatorValue() to schemaFor(it.createType()) }
        val mapping = branches.associate { (name, schema) -> name to (schema["\$ref"] ?: JsonStr(name)) }
        return jsonObj {
            put("oneOf", jsonArr(branches.map { (_, schema) -> schema }))
            put(
                "discriminator",
                jsonObj {
                    "propertyName" to UNION_DISCRIMINATOR
                    put("mapping", JsonObj(mapping))
                },
            )
        }
    }

    private fun classSchema(kclass: KClass<*>): JsonObj {
        val required = mutableListOf<String>()
        val properties = LinkedHashMap<String, JsonObj>()

        for (parameter in requireNotNull(kclass.primaryConstructor).parameters) {
            val name = parameter.name ?: continue
            properties[name] = schemaFor(parameter.type)
            // A property with a default can be left out of the payload; a
            // nullable one can be sent as null. Neither is required.
            if (!parameter.type.isMarkedNullable && !parameter.isOptional) required += name
        }

        return jsonObj {
            "type" to "object"
            put("properties", JsonObj(properties))
            if (required.isNotEmpty()) put("required", jsonStrings(required))
        }
    }

    /**
     * The schema of a type argument — an element, or a map's value.
     *
     * A star projection carries no type and a raw `List` has no argument at
     * all; either way the honest answer is that anything may be there.
     */
    private fun elementAt(type: KType, index: Int): JsonObj =
        type.arguments.getOrNull(index)?.type?.let(::schemaFor) ?: emptySchema

    /** Registers [body] under the class's own name and returns a ref to it. */
    private fun named(kclass: KClass<*>, body: () -> JsonObj): JsonObj {
        val name = requireNotNull(kclass.simpleName)
        if (components.isRegistered(name) || name in inProgress) return components.ref(name)

        inProgress += name
        val schema = try {
            body()
        } finally {
            inProgress -= name
        }
        components.register(name, schema)
        return components.ref(name)
    }
}

/** `{}` — every value is valid, which is what an unconstrained element is. */
private val emptySchema = JsonObj(emptyMap())

private val objectSchema = jsonObj { "type" to "object" }

private fun prim(type: String, format: String? = null) = jsonObj {
    "type" to type
    putIfNotNull("format", format)
}

/** The types with no structure to walk, and what each is called in a document. */
private val primitives: Map<KClass<*>, JsonObj> = mapOf(
    String::class to prim("string"),
    Boolean::class to prim("boolean"),
    Byte::class to prim("integer", "int32"),
    Short::class to prim("integer", "int32"),
    Int::class to prim("integer", "int32"),
    Long::class to prim("integer", "int64"),
    Float::class to prim("number", "float"),
    Double::class to prim("number", "double"),
    BigInteger::class to prim("integer"),
    BigDecimal::class to prim("number"),
)

/** And what each of the string-shaped scalars is called. */
private val stringFormats: Map<KClass<*>, String> = mapOf(
    UUID::class to "uuid",
    Instant::class to "date-time",
    OffsetDateTime::class to "date-time",
    ZonedDateTime::class to "date-time",
    LocalDateTime::class to "date-time",
    LocalDate::class to "date",
    LocalTime::class to "time",
    Duration::class to "duration",
)
