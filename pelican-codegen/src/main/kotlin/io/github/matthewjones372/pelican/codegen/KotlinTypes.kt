package io.github.matthewjones372.pelican.codegen

import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.PlainCodec
import io.github.matthewjones372.pelican.emptyJsonObj

/**
 * Which codec the generated declarations are annotated for.
 *
 * A sealed hierarchy is the one shape no JSON library can read off the Kotlin
 * alone: nothing in `sealed interface Payment` says which property carries the
 * branch. The two libraries spell that differently, so the file is annotated
 * for one of them.
 *
 * [JACKSON] is the default and costs a document with no union nothing.
 * [KOTLINX] is not free the same way: with no reflective fallback, every
 * generated payload type carries `@Serializable`.
 */
enum class CodecAnnotations { JACKSON, KOTLINX }

/**
 * Schemas in, Kotlin declarations out. It reads JSON Schema and never a
 * `KType`, which keeps the generated payload types in step with the
 * *documented* ones rather than with a second opinion about the same classes.
 *
 * Registered first and written last, because a `oneOf` reached halfway through
 * a document can decide that a class declared near the top belongs to a
 * hierarchy.
 */
class KotlinTypes(private val annotations: CodecAnnotations = CodecAnnotations.JACKSON) {

    private val declarations = LinkedHashMap<String, Shape>()
    private val declaring = mutableSetOf<String>()

    /** Constants -> the enum already generated for them, so one enum serves many uses. */
    private val enums = LinkedHashMap<List<String>, String>()
    private val taken = mutableSetOf<String>()

    /**
     * Shapes written where they were used, under the name they were hoisted
     * to. Rendering is a fixed point — see [settle] — and a hoist that named a
     * second class on the second pass would never reach one.
     */
    private val inlined = LinkedHashMap<String, String>()

    /** Component name -> the Kotlin name a `discriminator.mapping` gave it instead. */
    private val renames = LinkedHashMap<String, String>()

    /** Class name -> the hierarchies it is a branch of. */
    private val hierarchies = LinkedHashMap<String, MutableList<Adoption>>()

    /** What the generated file has to import for the annotations written into it. */
    private val imports = sortedSetOf<String>()

    private var components: JsonObj = emptyJsonObj

    fun declarations(): List<String> = settle()

    /**
     * Imports the declarations need. Written after [declarations], which is
     * when an annotation first exists to need one.
     */
    fun imports(): Set<String> = imports.toSet()

    /** Declares every named component the spec's schema source registered. */
    fun declareAll(components: JsonObj) {
        this.components = components
        collectRenames(components)
        components.fields.forEach { (name, schema) -> declare(name, schema as JsonObj) }
        // Settled here too, so a document's own component names are taken
        // before anything written inline can claim one.
        settle()
    }

    /** What the generated file calls a component the document named. */
    fun kotlinName(component: String): String = renames[component] ?: typeName(component)

    // ------------------------------------------------------------ registering

    private fun declare(rawName: String, schema: JsonObj): String {
        val name = kotlinName(rawName)
        // Reserved before recursing, so a type that refers to itself terminates.
        if (name in declarations || name in declaring) return name
        declaring += name
        taken += name
        declarations[name] = shapeOf(name, rawName, schema)
        declaring -= name
        return name
    }

    private fun shapeOf(name: String, documentName: String, schema: JsonObj): Shape {
        when (val composed = composed(schema, components, documentName)) {
            is Composed.Union -> return hierarchy(name, composed)
            is Composed.Merged -> return Shape.Klass(composed.schema)
            else -> Unit
        }
        val properties = schema["properties"] as? JsonObj
        val constants = stringConstants(schema)
        return when {
            properties != null && !properties.isEmpty -> Shape.Klass(schema)

            // Remembered by its constants too, so an inline copy reuses it.
            constants != null -> Shape.Enum(constants).also { enums.putIfAbsent(constants, name) }

            else -> Shape.Alias(schema)
        }
    }

    /**
     * The sealed interface, and the class each branch becomes. A branch that is
     * a reference gets no class of its own — the component already is that
     * class, and is taught here that it belongs to a hierarchy.
     */
    private fun hierarchy(name: String, union: Composed.Union): Shape {
        val branches = union.branches.map { branch ->
            val target = branch.ref?.let { ref -> (components[ref] as? JsonObj)?.let { declare(ref, it) } }
                ?: declare(unique(branch.name(name), taken), branch.schema)
            hierarchies.getOrPut(target) { mutableListOf() } += Adoption(name, union.discriminator, branch.wire)
            target to branch.wire
        }
        return Shape.Sealed(union.discriminator, branches)
    }

    /**
     * The renames a `discriminator.mapping` asks for, read before anything is
     * declared: `mapping: { card: '#/.../CardPayment' }` means the generated
     * class is `Card`. A key colliding with a component's own name is left
     * alone — two schemas under one name is worse than one under two.
     */
    private fun collectRenames(components: JsonObj) {
        val declared = components.fields.keys.map { typeName(it) }.toSet()
        components.fields.forEach { (component, schema) ->
            val union = composed(schema as? JsonObj ?: return@forEach, components, component) as? Composed.Union
            union?.branches?.forEach { branch ->
                val ref = branch.ref ?: return@forEach
                val name = branch.name("")
                if (name != typeName(ref) && name !in declared) renames.putIfAbsent(ref, name)
            }
        }
    }

    // -------------------------------------------------------------- rendering

    /**
     * Every declaration written out, and again until nothing changes.
     *
     * Rendering a class resolves its property types, and that can declare
     * another which tells an already-written class which hierarchy it belongs
     * to. The fixed point is what stops the document's own order mattering.
     */
    private fun settle(): List<String> {
        var written = emptyMap<String, String>()
        while (true) {
            val next = declarations.keys.toList().associateWith { render(it) }
            if (next == written) return next.values.toList()
            written = next
        }
    }

    private fun render(name: String): String = when (val shape = declarations.getValue(name)) {
        is Shape.Enum -> enumDeclaration(name, shape.constants)
        is Shape.Alias -> "typealias $name = ${type(shape.schema, name)}"
        is Shape.Klass -> dataClass(name, shape.schema)
        is Shape.Sealed -> sealedInterface(name, shape)
    }

    /** A schema fragment as a Kotlin type expression. [context] names anything hoisted out of it. */
    fun type(schema: JsonValue?, context: String): String {
        val obj = schema as? JsonObj ?: return "Any?"
        val base = base(obj, context)
        return if (obj.admitsNull() && !base.endsWith("?")) "$base?" else base
    }

    private fun base(obj: JsonObj, context: String): String {
        val named = (obj["\$ref"] as? JsonStr)?.let { kotlinName(it.value.substringAfterLast('/')) }
            ?: stringConstants(obj)?.let { enumFor(context, it) }
            // A union or a merge written where it was used has to be hoisted:
            // neither is a type expression, and both are a declaration.
            ?: hoistComposed(obj, context)
            ?: (obj["allOf"] as? JsonArr)?.items?.singleOrNull()?.let { type(it, context) }
            // `anyOf` of one real branch and a null one is how 3.1 spells a
            // nullable reference; the null branch is already accounted for by
            // `admitsNull`, so what is left is the type. Anything richer is
            // read by `composed` above, or is a union this generator does not
            // model and falls through below.
            ?: obj.anyOfBranches()?.singleOrNull()?.let { type(it, context) }
        if (named != null) return named

        return scalarType(obj, context)
    }

    private fun hoistComposed(obj: JsonObj, context: String): String? = when (composed(obj, components)) {
        is Composed.Union, is Composed.Merged -> hoist(context, obj)
        else -> null
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
            return hoist(context, obj)
        }
        return when (val additional = obj["additionalProperties"]) {
            is JsonObj -> "Map<String, ${type(additional, context + "Value")}>"
            else -> "Map<String, Any?>"
        }
    }

    private fun hoist(context: String, obj: JsonObj): String =
        inlined.getOrPut("$context ${obj.render()}") { declare(unique(typeName(context), taken), obj) }

    private fun dataClass(name: String, schema: JsonObj): String {
        val adoptions = hierarchies[name].orEmpty()
        // The hierarchy carries the discriminator, so the branch does not: two
        // places holding one value is two places for them to disagree, and
        // kotlinx.serialization refuses the pair outright.
        val carried = adoptions.map { it.discriminator }.toSet()
        val properties = ((schema["properties"] as? JsonObj)?.fields).orEmpty() - carried
        val required = (schema["required"] as? JsonArr)?.items.orEmpty()
            .mapNotNull { (it as? JsonStr)?.value }
            .toSet()

        return buildString {
            (schema["description"] as? JsonStr)?.let { appendLine(kdoc(it.value, "")) }
            annotationsFor(adoptions).forEach { appendLine(it) }
            if (properties.isEmpty()) {
                append("class $name")
            } else {
                appendLine("data class $name(")
                properties.forEach { (property, propertySchema) ->
                    append(propertyDeclaration(name, property, propertySchema, property in required))
                }
                append(")")
            }
            append(adoptions.map { it.hierarchy }.distinct().joinToString().prefixed(" : "))
        }
    }

    private fun propertyDeclaration(
        owner: String,
        property: String,
        schema: JsonValue,
        required: Boolean,
    ): String = buildString {
        ((schema as? JsonObj)?.get("description") as? JsonStr)?.let { appendLine(kdoc(it.value, "    ")) }
        val declared = type(schema, owner + typeName(property))
        // An optional property is nullable with a default here, because
        // Kotlin has no other way to say "may be left out".
        val kotlinType = if (!required && !declared.endsWith("?")) "$declared?" else declared
        // The wire name is kept exactly, backticked where it has to be —
        // renaming it would need a codec-specific annotation on every
        // property that was renamed, where the only annotations this
        // generator writes are the ones a sealed hierarchy cannot be read
        // without.
        val propertyName = if (isIdentifier(property)) property else "`$property`"
        appendLine("    val $propertyName: $kotlinType${if (required) "" else " = null"},")
    }

    /**
     * What the sealed interface has to say to be readable: which property
     * carries the branch, and which string selects each one. Both are written
     * out rather than derived, because both are the document's and neither is
     * recoverable from the Kotlin.
     */
    private fun sealedInterface(name: String, shape: Shape.Sealed): String = buildString {
        appendLine(kdoc(hierarchyDoc(shape), ""))
        when (annotations) {
            CodecAnnotations.JACKSON -> {
                imports += JACKSON_ANNOTATIONS
                appendLine(
                    "@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, " +
                        "include = JsonTypeInfo.As.PROPERTY, property = ${kotlinString(shape.discriminator)})",
                )
                appendLine("@JsonSubTypes(")
                shape.branches.forEach { (branch, wire) ->
                    appendLine("    JsonSubTypes.Type(value = $branch::class, name = ${kotlinString(wire)}),")
                }
                appendLine(")")
            }

            CodecAnnotations.KOTLINX -> {
                imports += KOTLINX_ANNOTATIONS
                appendLine("@OptIn(ExperimentalSerializationApi::class)")
                appendLine("@Serializable")
                appendLine("@JsonClassDiscriminator(${kotlinString(shape.discriminator)})")
            }
        }
        append("sealed interface $name")
    }

    private fun hierarchyDoc(shape: Shape.Sealed): String =
        "One of ${shape.branches.joinToString(", ") { (branch, _) -> "[$branch]" }}, " +
            "told apart by `${shape.discriminator}`."

    /**
     * The annotations a branch class carries. Under Jackson there are none —
     * the hierarchy names its branches, so the branch does not have to name
     * itself — and under kotlinx.serialization every generated class needs
     * `@Serializable` whether or not it is one.
     */
    private fun annotationsFor(adoptions: List<Adoption>): List<String> =
        if (annotations != CodecAnnotations.KOTLINX) {
            emptyList()
        } else {
            imports += SERIALIZABLE
            listOf("@Serializable") + adoptions.take(1).map { "@SerialName(${kotlinString(it.wire)})" }
        }

    /** Reuses the enum already generated for these constants, or declares one. */
    private fun enumFor(context: String, constants: List<String>): String {
        enums[constants]?.let { return it }
        val name = unique(typeName(context), taken)
        enums[constants] = name
        declarations[name] = Shape.Enum(constants)
        return name
    }

    /**
     * A constant is written exactly as the document spells it, in backticks
     * where it has to be: `in-progress` is a legal enum constant name in
     * Kotlin, and `EnumCodec` matches on the constant's name, so the wire
     * value and the Kotlin name stay the same string. Renaming it to
     * `IN_PROGRESS` would need a codec-specific annotation to map back, which
     * is exactly what these declarations are meant to do without.
     */
    private fun enumDeclaration(name: String, constants: List<String>): String {
        val declared = "enum class $name { ${constants.joinToString(", ") { asWritten(it) }} }"
        if (annotations != CodecAnnotations.KOTLINX) return declared
        imports += SERIALIZABLE
        return "@Serializable\n$declared"
    }

    /**
     * The constants of a string enum, or null when this is not one — including
     * when a constant cannot be written as a Kotlin name at all, in which case
     * the value stays a `String` rather than being renamed into something that
     * no longer matches.
     */
    private fun stringConstants(obj: JsonObj): List<String>? {
        val values = (obj["enum"] as? JsonArr)?.items?.takeIf { it.isNotEmpty() } ?: return null
        val strings = values.mapNotNull { (it as? JsonStr)?.value }
        if (strings.size != values.size || strings.any { !isWritable(it) }) return null
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

/** What a declaration knows about itself before the file is written. */
private sealed interface Shape {
    class Klass(val schema: JsonObj) : Shape
    class Alias(val schema: JsonObj) : Shape
    class Enum(val constants: List<String>) : Shape
    class Sealed(val discriminator: String, val branches: List<Pair<String, String>>) : Shape
}

/** A class's membership of one hierarchy, and the value that selects it there. */
private class Adoption(val hierarchy: String, val discriminator: String, val wire: String)

private fun String.prefixed(prefix: String) = if (isEmpty()) "" else prefix + this

private val NULL_TYPE = JsonStr("null")

private val JACKSON_ANNOTATIONS = listOf(
    "com.fasterxml.jackson.annotation.JsonSubTypes",
    "com.fasterxml.jackson.annotation.JsonTypeInfo",
)

private val KOTLINX_ANNOTATIONS = listOf(
    "kotlinx.serialization.ExperimentalSerializationApi",
    "kotlinx.serialization.SerialName",
    "kotlinx.serialization.Serializable",
    "kotlinx.serialization.json.JsonClassDiscriminator",
)

private val SERIALIZABLE = listOf("kotlinx.serialization.SerialName", "kotlinx.serialization.Serializable")

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
