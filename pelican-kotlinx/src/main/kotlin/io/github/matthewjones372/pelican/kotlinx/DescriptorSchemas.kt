package io.github.matthewjones372.pelican.kotlinx

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.jsonArr
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.jsonStrings
import io.github.matthewjones372.pelican.orNull
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Turns kotlinx.serialization descriptors into OpenAPI schemas.
 *
 * Named object types are hoisted into `#/components/schemas` and referenced,
 * so a type used by ten endpoints is described once. Everything else —
 * primitives, lists, maps, enums — is inlined. That is the same shape
 * swagger-core produces for the Jackson side, which is what lets the two
 * documents be compared.
 */
internal class DescriptorSchemas(
    private val components: SchemaComponents,
    /** What the configured `Json` calls the discriminator when a class does not say. */
    private val discriminator: String = "type",
) {

    /** Names currently being built, so a recursive type terminates at its ref. */
    private val inProgress = mutableSetOf<String>()

    fun schemaFor(desc: SerialDescriptor): JsonObj {
        val base = build(desc)
        // `orNull` rather than a `nullable: true` written here, so that this and
        // the swagger-core side cannot drift into two spellings of one fact.
        return if (desc.isNullable) base.orNull() else base
    }

    private fun build(desc: SerialDescriptor): JsonObj = when (desc.kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> prim("string")

        PrimitiveKind.BOOLEAN -> prim("boolean")

        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT -> prim("integer", "int32")

        PrimitiveKind.LONG -> prim("integer", "int64")

        PrimitiveKind.FLOAT -> prim("number", "float")

        PrimitiveKind.DOUBLE -> prim("number", "double")

        // Inlined rather than hoisted, which is what swagger-core does for the
        // Jackson side. An enum is small enough that a shared definition buys
        // little, and agreeing is worth more than saving the bytes.
        SerialKind.ENUM -> jsonObj {
            "type" to "string"
            put("enum", jsonStrings((0 until desc.elementsCount).map(desc::getElementName)))
        }

        StructureKind.LIST -> jsonObj {
            "type" to "array"
            put("items", schemaFor(desc.getElementDescriptor(0)))
        }

        StructureKind.MAP -> jsonObj {
            "type" to "object"
            put("additionalProperties", schemaFor(desc.getElementDescriptor(1)))
        }

        StructureKind.OBJECT -> jsonObj { "type" to "object" }

        StructureKind.CLASS -> named(desc) { classSchema(desc) }

        PolymorphicKind.SEALED -> named(desc) { union(desc) }

        // An open hierarchy is not a closed union: the subclasses are whatever
        // was registered in a module at runtime, and a `oneOf` listing the ones
        // this JVM happens to know would be a narrower document than the code.
        PolymorphicKind.OPEN -> jsonObj {
            "type" to "object"
            "description" to "polymorphic: ${desc.serialName}"
        }

        else -> jsonObj { "type" to "object" }
    }

    /**
     * A sealed hierarchy as the union it is: `oneOf` over the branches, with
     * the `discriminator` saying which property tells them apart and which
     * value picks each one.
     *
     * Written out rather than left implicit because the mapping is the half a
     * reader cannot recover. `@SerialName("card")` on a class called `Card` is
     * the difference between a document another tool can decode and one it can
     * only guess at — and `pelican-import` reads exactly this shape back.
     *
     * A sealed descriptor carries its branches under element 1, which is
     * kotlinx's own framing of `{ "type": ..., "value": ... }`; the element
     * *names* there are the serial names, which is what travels.
     */
    private fun union(desc: SerialDescriptor): JsonObj {
        val branches = desc.getElementDescriptor(1)
        val refs = (0 until branches.elementsCount).map { i ->
            branches.getElementName(i) to schemaFor(branches.getElementDescriptor(i))
        }
        val mapping = refs.associate { (name, ref) -> name to (ref["${'$'}ref"] ?: JsonStr(name)) }
        return jsonObj {
            put("oneOf", jsonArr(refs.map { (_, ref) -> ref }))
            put(
                "discriminator",
                jsonObj {
                    "propertyName" to discriminatorOf(desc)
                    put("mapping", JsonObj(mapping))
                },
            )
        }
    }

    private fun discriminatorOf(desc: SerialDescriptor): String =
        desc.annotations.filterIsInstance<JsonClassDiscriminator>().firstOrNull()?.discriminator ?: discriminator

    /** Registers [body] under the descriptor's short name and returns a ref to it. */
    private fun named(desc: SerialDescriptor, body: () -> JsonObj): JsonObj {
        val name = shortName(desc.serialName)
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

    private fun classSchema(desc: SerialDescriptor): JsonObj {
        val required = mutableListOf<String>()
        val properties = LinkedHashMap<String, JsonObj>()

        for (i in 0 until desc.elementsCount) {
            val child = desc.getElementDescriptor(i)
            val name = desc.getElementName(i)
            properties[name] = schemaFor(child)
            // A property with a default can be left out of the payload; a
            // nullable one can be sent as null. Neither is required.
            if (!child.isNullable && !desc.isElementOptional(i)) required += name
        }

        return jsonObj {
            "type" to "object"
            put("properties", JsonObj(properties))
            if (required.isNotEmpty()) put("required", jsonStrings(required))
        }
    }

    private fun prim(type: String, format: String? = null) = jsonObj {
        "type" to type
        putIfNotNull("format", format)
    }

    private fun shortName(serialName: String) =
        serialName.removeSuffix("?").substringAfterLast('.')
}
