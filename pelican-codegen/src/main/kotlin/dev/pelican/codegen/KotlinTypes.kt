package dev.pelican.codegen

import dev.pelican.JsonArr
import dev.pelican.JsonObj
import dev.pelican.JsonStr
import dev.pelican.JsonValue
import dev.pelican.PlainCodec

/**
 * Schemas in, Kotlin declarations out.
 *
 * The schemas are whatever the spec's `SchemaSource` produced — swagger-core's
 * for Jackson, the descriptor walker for kotlinx.serialization — so this reads
 * JSON Schema and nothing else. It never sees a `KType`, which is what keeps
 * the generated payload types in step with the *documented* ones rather than
 * with a second opinion about the same Kotlin classes.
 */
internal class KotlinTypes {

    private val declarations = LinkedHashMap<String, String>()
    private val declaring = mutableSetOf<String>()

    /** Constants -> the enum already generated for them, so one enum serves many uses. */
    private val enums = LinkedHashMap<List<String>, String>()
    private val taken = mutableSetOf<String>()

    fun declarations(): List<String> = declarations.values.toList()

    /** Declares every named component the spec's schema source registered. */
    fun declareAll(components: JsonObj) {
        components.fields.forEach { (name, schema) -> declare(name, schema as JsonObj) }
    }

    private fun declare(rawName: String, schema: JsonObj): String {
        val name = typeName(rawName)
        // Reserved before recursing, so a type that refers to itself terminates.
        if (name in declarations || name in declaring) return name
        declaring += name
        taken += name

        val constants = stringConstants(schema)
        val properties = schema["properties"] as? JsonObj
        declarations[name] = when {
            properties != null && !properties.isEmpty -> dataClass(name, schema, properties)
            constants != null -> enumClass(name, constants)
            else -> "typealias $name = ${type(schema, name)}"
        }

        declaring -= name
        return name
    }

    /** A schema fragment as a Kotlin type expression. [context] names anything hoisted out of it. */
    fun type(schema: JsonValue?, context: String): String {
        val obj = schema as? JsonObj ?: return "Any?"
        val base = base(obj, context)
        return if (obj.admitsNull() && !base.endsWith("?")) "$base?" else base
    }

    private fun base(obj: JsonObj, context: String): String {
        val named = (obj["\$ref"] as? JsonStr)?.let { typeName(it.value.substringAfterLast('/')) }
            ?: stringConstants(obj)?.let { enumFor(context, it) }
            ?: (obj["allOf"] as? JsonArr)?.items?.singleOrNull()?.let { type(it, context) }
            // `anyOf` of one real branch and a null one is how 3.1 spells a
            // nullable reference; the null branch is already accounted for by
            // `admitsNull`, so what is left is the type. Anything richer is a
            // union this generator does not model, and falls through below.
            ?: obj.anyOfBranches()?.singleOrNull()?.let { type(it, context) }
        if (named != null) return named

        return scalarType(obj, context)
    }

    private fun scalarType(obj: JsonObj, context: String): String = when (obj.scalarType()) {
        "string" -> "String"

        "integer" -> when ((obj["format"] as? JsonStr)?.value) {
            "int32" -> "Int"
            else -> "Long"
        }

        "number" -> if ((obj["format"] as? JsonStr)?.value == "float") "Float" else "Double"

        "boolean" -> "Boolean"

        "array" -> "List<${type(obj["items"], context + "Item")}>"

        "object" -> objectType(obj, context)

        // A schema with properties and no `type` is still an object.
        null -> if (obj["properties"] != null) objectType(obj, context) else "Any?"

        // A shape this generator does not model becomes `Any?` rather than a
        // guess — honest, and it still compiles.
        else -> "Any?"
    }

    private fun objectType(obj: JsonObj, context: String): String {
        val properties = obj["properties"] as? JsonObj
        if (properties != null && !properties.isEmpty) {
            // An inline object needs a name to be a Kotlin type at all, so it is
            // hoisted to one built from where it appeared: Order.shipping ->
            // OrderShipping.
            return declare(unique(typeName(context), taken), obj)
        }
        return when (val additional = obj["additionalProperties"]) {
            is JsonObj -> "Map<String, ${type(additional, context + "Value")}>"
            else -> "Map<String, Any?>"
        }
    }

    private fun dataClass(name: String, schema: JsonObj, properties: JsonObj): String {
        val required = (schema["required"] as? JsonArr)?.items.orEmpty()
            .mapNotNull { (it as? JsonStr)?.value }
            .toSet()

        return buildString {
            (schema["description"] as? JsonStr)?.let { appendLine(kdoc(it.value, "")) }
            appendLine("data class $name(")
            properties.fields.forEach { (property, propertySchema) ->
                ((propertySchema as? JsonObj)?.get("description") as? JsonStr)?.let {
                    appendLine(kdoc(it.value, "    "))
                }
                val declared = type(propertySchema, name + typeName(property))
                // An optional property is nullable with a default here, because
                // Kotlin has no other way to say "may be left out".
                val optional = property !in required
                val kotlinType = if (optional && !declared.endsWith("?")) "$declared?" else declared
                // The wire name is kept exactly, backticked where it has to be —
                // renaming it would need a codec-specific annotation, and this
                // file is meant to work with whichever codec you pass it.
                val propertyName = if (isIdentifier(property)) property else "`$property`"
                appendLine("    val $propertyName: $kotlinType${if (optional) " = null" else ""},")
            }
            append(")")
        }
    }

    private fun enumClass(name: String, constants: List<String>): String {
        enums.putIfAbsent(constants, name)
        return "enum class $name { ${constants.joinToString(", ")} }"
    }

    /** Reuses the enum already generated for these constants, or declares one. */
    private fun enumFor(context: String, constants: List<String>): String {
        enums[constants]?.let { return it }
        val name = unique(typeName(context), taken)
        enums[constants] = name
        declarations[name] = "enum class $name { ${constants.joinToString(", ")} }"
        return name
    }

    /**
     * The constants of a string enum, or null when this is not one — including
     * when a constant is not a legal Kotlin name, in which case the value stays
     * a `String` rather than being renamed into something that no longer matches.
     */
    private fun stringConstants(obj: JsonObj): List<String>? {
        val values = (obj["enum"] as? JsonArr)?.items?.takeIf { it.isNotEmpty() } ?: return null
        val strings = values.mapNotNull { (it as? JsonStr)?.value }
        if (strings.size != values.size || strings.any { !isIdentifier(it) }) return null
        return strings
    }

    /**
     * Whether the schema admits null, in either of the two shapes OpenAPI 3.1
     * has for it: a `"null"` among the types, or a `"null"` branch of an
     * `anyOf` — the latter being what a nullable `$ref` has to become, since a
     * reference has no `type` of its own to widen.
     *
     * `nullable: true` is not read, and not as a kindness to older documents
     * either: it is 3.0's keyword, the emitter no longer writes it, and
     * accepting it here would mean this generator understood a document shape
     * nothing in this repository can produce or test.
     */
    private fun JsonObj.admitsNull(): Boolean =
        NULL_TYPE in ((this["type"] as? JsonArr)?.items.orEmpty()) ||
            anyOf()?.any { it.isNullSchema() } == true

    /** The `anyOf` branches that are not merely "or null", or null if there is no `anyOf`. */
    private fun JsonObj.anyOfBranches(): List<JsonValue>? = anyOf()?.filterNot { it.isNullSchema() }

    private fun JsonObj.anyOf(): List<JsonValue>? = (this["anyOf"] as? JsonArr)?.items

    private fun JsonValue.isNullSchema(): Boolean = (this as? JsonObj)?.get("type") == NULL_TYPE

    /**
     * The one type this schema is, ignoring a `"null"` beside it — the union
     * `["string", "null"]` describes a String that may be absent, and it is the
     * `?` that carries the second half.
     */
    private fun JsonObj.scalarType(): String? = when (val type = this["type"]) {
        is JsonStr -> type.value
        is JsonArr -> (type.items.filterNot { it == NULL_TYPE }.singleOrNull() as? JsonStr)?.value
        else -> null
    }
}

private val NULL_TYPE = JsonStr("null")

/** A path, query or header value, which always travels as a string, as a Kotlin type. */
internal fun plainType(codec: PlainCodec<*>, types: KotlinTypes, context: String): String {
    codec.enumValues?.takeIf { constants -> constants.isNotEmpty() && constants.all(::isIdentifier) }
        ?.let { constants ->
            return types.type(
                JsonObj(mapOf("enum" to JsonArr(constants.map { JsonStr(it) }))),
                context,
            )
        }
    return when (codec.openApiType) {
        "integer" -> if (codec.openApiFormat == "int64") "Long" else "Int"
        "number" -> "Double"
        "boolean" -> "Boolean"
        else -> "String"
    }
}
