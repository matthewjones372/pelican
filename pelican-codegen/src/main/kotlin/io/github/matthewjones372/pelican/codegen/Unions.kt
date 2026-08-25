package io.github.matthewjones372.pelican.codegen

import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.jsonStrings

/**
 * Schemas built out of other schemas, read once.
 */
sealed interface Composed {

    /** Nothing here changes what the schema already was. */
    data object Plain : Composed

    /** A sealed hierarchy: one interface, one class per branch. */
    class Union(val discriminator: String, val branches: List<Branch>) : Composed

    /** `allOf` of several schemas, flattened into the one class they describe together. */
    class Merged(val schema: JsonObj) : Composed

    /**
     * Composed, and not describable. [reason] is written for whoever has to
     * change the document, and is what the importer refuses with; [keyword] is
     * the field it is written under, so the refusal can name the position.
     */
    class Undescribable(val keyword: String, val reason: String) : Composed
}

/**
 * One arm of a union: the class it becomes and the wire value that selects it.
 * Separate, because a document mapping `"card"` to `CardPayment` has named the
 * branch twice and a reader's name is not the payload's string.
 */
class Branch internal constructor(
    /** The discriminator value that selects this branch. Never derived — always read. */
    val wire: String,
    val schema: JsonObj,
    /** The component this branch points at, or null where the document wrote it out here. */
    val ref: String?,
    private val mapped: String?,
    private val index: Int,
) {

    /** What this branch is called in Kotlin. The rule is [branchName]'s. */
    fun name(parent: String): String = branchName(parent, index, mapped, ref)
}

/**
 * What [schema] is, as far as the keywords that compose schemas are concerned.
 * [components] resolves the references an `allOf` has to be merged through, and
 * [name] is what the document calls this schema — needed only to find the
 * children of a hierarchy written the way 3.0 wrote them.
 */
fun composed(schema: JsonObj, components: JsonObj, name: String? = null): Composed {
    val oneOf = schema.realBranches("oneOf")
    val anyOf = schema.realBranches("anyOf")
    val allOf = (schema["allOf"] as? JsonArr)?.items.orEmpty()

    return when {
        oneOf.size > 1 -> union(schema, oneOf, components)
        anyOf.size > 1 -> Composed.Undescribable("anyOf", ANY_OF)
        allOf.size > 1 -> merge(schema, allOf, components)
        schema["discriminator"] != null -> inherited(name, schema, components)
        else -> Composed.Plain
    }
}

/**
 * The same hierarchy in OpenAPI 3.0's spelling: a parent carrying the
 * `discriminator`, each child an `allOf` of it. Nothing points down, so the
 * branches are found by looking for the schemas that point up.
 *
 * Read as well as 3.1's `oneOf` because swagger-core still emits it for an
 * annotated Jackson hierarchy — so a document Pelican publishes is one, and a
 * round trip that could not read its own output back would be no round trip.
 *
 * Without a `mapping` the selecting value is the branch's own schema name,
 * which is OpenAPI's implicit mapping.
 */
private fun inherited(name: String?, schema: JsonObj, components: JsonObj): Composed {
    val property = ((schema["discriminator"] as? JsonObj)?.get("propertyName") as? JsonStr)?.value
        ?: return Composed.Undescribable("discriminator", DISCRIMINATOR_ALONE)

    val children = name?.let { parent ->
        components.fields.filterValues { child -> (child as? JsonObj)?.extends(parent) == true }.keys
    }.orEmpty()
    if (children.isEmpty()) return Composed.Undescribable("discriminator", DISCRIMINATOR_ALONE)

    val mapping = ((schema["discriminator"] as? JsonObj)?.get("mapping") as? JsonObj)?.fields.orEmpty()
        .mapValues { (_, target) -> (target as? JsonStr)?.value?.substringAfterLast('/') }

    val branches = children.mapIndexed { i, child ->
        val mapped = mapping.entries.firstOrNull { it.value == child }?.key
        Branch(mapped ?: child, JsonObj(mapOf(REF to JsonStr(child))), child, mapped, i)
    }

    val nested = branches.filter { branch ->
        branch.ref?.let { ref -> (components[ref] as? JsonObj).isHierarchy(components, ref) } == true
    }
    if (nested.isNotEmpty()) {
        return Composed.Undescribable("discriminator", nestedHierarchy(property, nested.map { it.wire }))
    }

    return Composed.Union(property, branches)
}

/** Whether this schema is a child of [parent] in the 3.0 spelling. */
private fun JsonObj.extends(parent: String): Boolean =
    (this["allOf"] as? JsonArr)?.items.orEmpty().any { branch ->
        ((branch as? JsonObj)?.get(REF) as? JsonStr)?.value?.substringAfterLast('/') == parent
    }

private fun union(schema: JsonObj, branches: List<JsonObj>, components: JsonObj): Composed {
    val property = ((schema["discriminator"] as? JsonObj)?.get("propertyName") as? JsonStr)?.value
        ?: return Composed.Undescribable("oneOf", NO_DISCRIMINATOR)

    val mapping = ((schema["discriminator"] as? JsonObj)?.get("mapping") as? JsonObj)?.fields.orEmpty()
        .mapValues { (_, target) -> (target as? JsonStr)?.value?.substringAfterLast('/') }

    val read = branches.mapIndexed { i, branch ->
        val ref = (branch["\$ref"] as? JsonStr)?.value?.substringAfterLast('/')
        val mapped = mapping.entries.firstOrNull { it.value != null && it.value == ref }?.key
        val wire = mapped ?: ref ?: constant(branch, property, components)
            ?: return Composed.Undescribable("oneOf", unnamed(i, property))
        Branch(wire, branch, ref, mapped, i)
    }

    val repeated = read.groupBy { it.wire }.filterValues { it.size > 1 }.keys
    if (repeated.isNotEmpty()) return Composed.Undescribable("oneOf", repeatedValues(property, repeated))

    val nested = read.filter { branch -> resolve(branch.schema, components).isHierarchy(components, branch.ref) }
    if (nested.isNotEmpty()) {
        return Composed.Undescribable("oneOf", nestedHierarchy(property, nested.map { it.wire }))
    }

    return Composed.Union(property, read)
}

/**
 * Whether this schema is itself a hierarchy, asked of a branch of another one.
 */
private fun JsonObj?.isHierarchy(components: JsonObj, name: String?): Boolean {
    if (this == null) return false
    if ((this["discriminator"] as? JsonObj)?.get("propertyName") == null) return false
    if (realBranches("oneOf").size > 1) return true
    return name != null && components.fields.values.any { (it as? JsonObj)?.extends(name) == true }
}

/**
 * The discriminator value a branch declares for itself, which is how a
 * document names an inline branch: `kind: { const: "card" }`, or the
 * single-valued `enum` that says the same thing.
 */
private fun constant(branch: JsonObj, property: String, components: JsonObj): String? {
    val declared = (resolve(branch, components)?.get("properties") as? JsonObj)?.get(property) as? JsonObj
        ?: return null
    (declared["const"] as? JsonStr)?.let { return it.value }
    val values = (declared["enum"] as? JsonArr)?.items.orEmpty()
    return (values.singleOrNull() as? JsonStr)?.value
}

// ------------------------------------------------------------------- merging

/**
 * `allOf` flattened into the one class it describes.
 */
private fun merge(schema: JsonObj, branches: List<JsonValue>, components: JsonObj): Composed {
    val properties = LinkedHashMap<String, JsonValue>()
    val clashes = LinkedHashSet<String>()
    val required = LinkedHashSet<String>()
    var described: String? = (schema["description"] as? JsonStr)?.value

    val parts = flatten(listOf(schema.without("allOf")) + branches, components, mutableSetOf())
        ?: return Composed.Undescribable("allOf", UNRESOLVED)

    parts.forEach { part ->
        if (!part.mergeable()) return Composed.Undescribable("allOf", NOT_AN_OBJECT)
        ((part["properties"] as? JsonObj)?.fields).orEmpty().forEach { (name, declared) ->
            val existing = properties.put(name, declared)
            if (existing != null && existing != declared) clashes += name
        }
        required += (part["required"] as? JsonArr)?.items.orEmpty().mapNotNull { (it as? JsonStr)?.value }
        described = described ?: (part["description"] as? JsonStr)?.value
    }

    if (clashes.isNotEmpty()) return Composed.Undescribable("allOf", collision(clashes))
    if (properties.isEmpty()) return Composed.Undescribable("allOf", NOTHING_TO_MERGE)

    return Composed.Merged(
        jsonObj {
            putIfNotNull("description", described)
            "type" to "object"
            put("properties", JsonObj(properties))
            if (required.isNotEmpty()) put("required", jsonStrings(required.toList()))
        },
    )
}

/**
 * Every branch resolved, with a branch that is itself an `allOf` folded in.
 */
private fun flatten(branches: List<JsonValue>, components: JsonObj, seen: MutableSet<String>): List<JsonObj>? =
    buildList {
        branches.forEach { branch ->
            val reference = ((branch as? JsonObj)?.get(REF) as? JsonStr)?.value
            if (reference != null && !seen.add(reference)) return null
            val resolved = resolve(branch, components) ?: return null
            val nested = (resolved["allOf"] as? JsonArr)?.items
            if (nested == null) {
                add(resolved)
            } else {
                addAll(flatten(listOf(resolved.without("allOf")) + nested, components, seen) ?: return null)
            }
        }
    }

/** A branch contributes properties or it contributes nothing; anything else is not a merge. */
private fun JsonObj.mergeable(): Boolean {
    val type = (this["type"] as? JsonStr)?.value
    return (type == null || type == "object") && this["oneOf"] == null && this["anyOf"] == null
}

private fun resolve(branch: JsonValue, components: JsonObj): JsonObj? {
    val obj = branch as? JsonObj ?: return null
    val ref = (obj["\$ref"] as? JsonStr)?.value ?: return obj
    return components[ref.substringAfterLast('/')] as? JsonObj
}

private fun JsonObj.without(key: String) = JsonObj(fields - key)

/** The branches that say something other than "or null". */
private fun JsonObj.realBranches(key: String): List<JsonObj> =
    (this[key] as? JsonArr)?.items.orEmpty()
        .filterIsInstance<JsonObj>()
        .filterNot { it["type"] == NULL }

private val NULL = JsonStr("null")

private const val REF = "\u0024ref"

// -------------------------------------------------------------- what to say

private const val ANY_OF =
    "A value that is any of several shapes. `anyOf` allows a payload to satisfy two branches at once, " +
        "and a Kotlin value is one class or the other — so a sealed hierarchy would say something " +
        "narrower than the document does. Write it as a `oneOf` with a `discriminator` if the branches " +
        "are exclusive, or declare the one shape you mean."

private const val NO_DISCRIMINATOR =
    "A value that is one of several shapes, with nothing saying which. Kotlin holds that as a sealed " +
        "hierarchy, and a decoder needs to know which branch a payload is before it reads it — add a " +
        "`discriminator` naming the property that says so, and this becomes a sealed interface with one " +
        "class per branch."

private const val DISCRIMINATOR_ALONE =
    "A `discriminator` with no branches to discriminate. Neither spelling of a hierarchy is here: no " +
        "`oneOf` listing the branches, and no other schema declaring an `allOf` of this one. List the " +
        "branches under a `oneOf` beside the `discriminator` and this becomes a sealed interface with a " +
        "class per branch."

private const val UNRESOLVED =
    "Several schemas merged into one, and one of them is a reference this cannot follow: it points at " +
        "nothing, or at a merge that includes itself."

private const val NOT_AN_OBJECT =
    "Several schemas merged into one, and one of them is not an object. Merging produces a class, and a " +
        "class has properties; declare the merged shape in the document instead."

private const val NOTHING_TO_MERGE =
    "Several schemas merged into one, and between them they declare no properties."

private fun collision(names: Collection<String>): String =
    "Several schemas merged into one, and they disagree about ${names.joinToString(", ") { "`$it`" }}. " +
        "Merging would have to pick a winner, and the generated class would then accept payloads the " +
        "document rejects — declare the merged shape in the document, or make the branches agree."

private fun unnamed(index: Int, property: String): String =
    "A union branch at [$index] that nothing selects. It is neither a reference nor a schema declaring a " +
        "`$property`, so no discriminator value picks it out; give it a `const` for `$property`, or point " +
        "at a named schema and map it."

private fun repeatedValues(property: String, values: Collection<String>): String =
    "A union whose branches share a `$property` of ${values.joinToString(", ") { "`$it`" }}. Two branches " +
        "selected by one value is a document a decoder cannot follow."

/**
 * A hierarchy inside a hierarchy, which is a Kotlin shape and not a wire one.
 *
 * `sealed interface Inner : Outer` is a declaration Kotlin has, and both codecs
 * hold it — but neither puts *two* type ids on a payload, and both say so
 * deliberately. Jackson resolves the declared base type's id and no other,
 * refused as an extension since 2013 (jackson-databind#2957);
 * kotlinx.serialization makes a second `@JsonClassDiscriminator` under the same
 * hierarchy a compile error, and flattens a nested sealed hierarchy to one
 * discriminator naming the leaf. So a document that spreads the type over two
 * properties describes a payload neither library writes or reads, and a sealed
 * interface extending another would generate cleanly and decode nothing.
 */
private fun nestedHierarchy(property: String, branches: Collection<String>): String {
    val named = branches.joinToString(", ") { "`$it`" }
    val subject =
        if (branches.size == 1) "branch $named is itself a union" else "branches $named are themselves unions"
    return "A union whose $subject. The type of a payload would be spread over two properties — `$property` " +
        "here and another below — and no JSON library reads a type at two levels: Jackson resolves one, and " +
        "kotlinx.serialization flattens the levels into one. Write the hierarchy flat instead: one `oneOf` " +
        "listing every leaf schema, and one `discriminator` whose `mapping` names each of them. The level " +
        "in between can stay a type in your own code; what it cannot be is a second value on the wire."
}
