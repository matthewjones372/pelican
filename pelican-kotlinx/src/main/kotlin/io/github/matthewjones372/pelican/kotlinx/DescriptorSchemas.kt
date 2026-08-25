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
 * Named object types are hoisted into `#/components/schemas`; everything else
 * is inlined. The same shape swagger-core produces for the Jackson side, which
 * is what lets the two documents be compared.
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
        // `orNull` rather than a `nullable: true` written here, so this and
        // the swagger-core side cannot drift into two spellings.
        return if (desc.isNullable) base.orNull() else base
    }

    private fun build(desc: SerialDescriptor): JsonObj = when (desc.kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> prim("string")

        PrimitiveKind.BOOLEAN -> prim("boolean")

        PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT -> prim("integer", "int32")

        PrimitiveKind.LONG -> prim("integer", "int64")

        PrimitiveKind.FLOAT -> prim("number", "float")

        PrimitiveKind.DOUBLE -> prim("number", "double")

        // Inlined rather than hoisted, matching swagger-core: agreeing is
        // worth more than the bytes a shared definition would save.
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

        // Not a closed union: subclasses are registered at runtime, so a
        // `oneOf` would describe less than the code allows.
        PolymorphicKind.OPEN -> jsonObj {
            "type" to "object"
            "description" to "polymorphic: ${desc.serialName}"
        }

        else -> jsonObj { "type" to "object" }
    }

    /**
     * A sealed hierarchy as `oneOf` plus a `discriminator`. The mapping is
     * written out because a reader cannot recover it: `@SerialName("card")` on
     * a class called `Card` is the difference between a document another tool
     * can decode and one it can only guess at.
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
            // A default may be left out and a nullable may be null.
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
