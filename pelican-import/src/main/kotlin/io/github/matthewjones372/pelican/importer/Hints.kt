package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The `discriminator` a document never wrote down, supplied by the reader.
 *
 * The `mapping` is written out because the wire value is the fact being
 * rescued — OpenAPI's implicit rule never applies here, so ignoring a branch's
 * `const` would publish `CardPayment` where the document said `card`.
 */
internal class Hints(private val declared: Map<String, String>) {

    private val failures = mutableListOf<Failure>()

    /**
     * Hint -> the `discriminator` object it wrote, held by identity.
     */
    private val written = LinkedHashMap<Hint, JsonObj>()

    /** [document] with a `discriminator` written in at each hinted position. */
    fun applyTo(document: JsonObj): JsonObj {
        if (declared.isEmpty()) return document

        val components = document.obj("components")?.obj("schemas") ?: JsonObj(emptyMap())
        val result = declared.entries.fold(document) { tree, (address, property) ->
            apply(Hint(address, property), tree, components)
        }
        failIfAny()
        return result
    }

    /**
     * Fails on a hint nothing generated needed.
     */
    fun failIfUnused(generated: List<JsonValue>) {
        if (written.isEmpty()) return

        val survived = Collections.newSetFromMap(IdentityHashMap<JsonObj, Boolean>())
        generated.forEach { collectDiscriminators(it, survived, mutableSetOf()) }

        written.forEach { (hint, discriminator) ->
            if (discriminator !in survived) failures += Failure(hint, hint.pointer(), UNREACHED)
        }
        failIfAny()
    }

    // ------------------------------------------------------------- applying

    private fun apply(hint: Hint, document: JsonObj, components: JsonObj): JsonObj = try {
        val steps = hint.steps()
        val target = steps.fold(document as JsonValue?) { node, step -> node.step(step) } as? JsonObj
            ?: reject(hint.pointer(), NOTHING_THERE)
        val discriminator = discriminatorFor(hint, target, components)
        written[hint] = discriminator
        replaced(document, steps, target.with("discriminator", discriminator)) as JsonObj
    } catch (rejected: Rejected) {
        failures += Failure(hint, rejected.path, rejected.message)
        document
    }

    /**
     * The `discriminator` this hint asks for, or a refusal saying why not.
     */
    private fun discriminatorFor(hint: Hint, schema: JsonObj, components: JsonObj): JsonObj {
        val branches = schema.branches()
        if (branches.size < 2) reject(hint.pointer(), notAUnion(schema))
        if (schema["discriminator"] != null) reject(hint.pointer(), stated(schema))

        val resolved = branches.map { branch -> branch to (branch.reference()?.let { components.obj(it) } ?: branch) }
        if (resolved.none { (_, target) -> target.obj("properties")?.get(hint.property) != null }) {
            reject(hint.pointer(), undeclared(hint.property, resolved.map { it.second }))
        }

        val wired = resolved.mapIndexed { index, (branch, target) ->
            val wire = target.constant(hint.property)
                ?: branch.reference()
                ?: reject(hint.pointer() / "oneOf" / index, unselectable(hint.property))
            wire to branch
        }

        val repeated = wired.groupBy { (wire, _) -> wire }.filterValues { it.size > 1 }.keys
        if (repeated.isNotEmpty()) reject(hint.pointer(), repeatedValues(hint.property, repeated))

        // A branch the document wrote out inline is not in the mapping and
        // cannot be: a `mapping` value is a reference, and an inline branch is
        // not referable. Its `const` is where its wire value already was.
        val mapping = wired.mapNotNull { (wire, branch) -> branch["\$ref"]?.let { wire to it } }.toMap()
        val named = jsonObjOf("propertyName" to JsonStr(hint.property))
        return if (mapping.isEmpty()) named else named.with("mapping", JsonObj(mapping))
    }

    // -------------------------------------------------------------- failures

    /**
     * Every wrong hint at once, for the reason [Problems] reports every
     * undescribable operation at once: what a reader does about three bad
     * hints is one edit to one block of the build file, and it cannot be made
     * from the first of them.
     */
    private fun failIfAny() {
        if (failures.isEmpty()) return
        val found = failures.toList()
        failures.clear()
        throw ImportFailure(
            buildString {
                append(found.size)
                append(if (found.size == 1) " discriminator hint" else " discriminator hints")
                appendLine(" cannot be used as written:")
                appendLine()
                found.forEach { failure ->
                    appendLine("  ${failure.hint}")
                    appendLine("    at ${failure.path}")
                    failure.message.lines().forEach { appendLine("    $it") }
                    appendLine()
                }
                append(
                    "A hint states what a document left unsaid, so it has to be true of the document as " +
                        "it stands. Correct it, or drop it and exclude the operations that reach the union.",
                )
            },
        )
    }

    private class Failure(val hint: Hint, val path: JsonPath, val message: String)
}

/**
 * One hint: which schema, and which of its properties tells the branches apart.
 *
 * - `Payment` — no slash, so a component: `#/components/schemas/Payment`.
 * - `Order/properties/payment` — relative to `#/components/schemas`, which is
 *   where a union written under a named schema lives.
 * - `#/paths/~1payments/post/requestBody/content/application~1json/schema` —
 *   a pointer from the root, for a union written at the endpoint. RFC 6901
 *   escaping, because a path template is made of slashes.
 *
 * The pointer addresses the document *as Pelican reads it*: bundled, so a
 * schema pulled in from another file is under `components/schemas` with the
 * name it had there, and converted, so a Swagger 2.0 document is addressed
 * under `components/schemas` rather than `definitions`.
 */
private class Hint(private val address: String, val property: String) {

    fun steps(): List<String> = when {
        address.startsWith("#/") || address.startsWith("/") -> address.removePrefix("#").split('/')
        '/' in address -> listOf("components", "schemas") + address.split('/')
        else -> listOf("components", "schemas", address)
    }.filter { it.isNotEmpty() }.map { it.replace("~1", "/").replace("~0", "~") }

    fun pointer(): JsonPath = steps().fold(JsonPath.root) { path, step -> path / step }

    /** As the build file writes it, so the failure and the line to fix read the same. */
    override fun toString(): String = "discriminator(\"$address\", property = \"$property\")"
}

/** A hint that does not hold, carrying the position it does not hold at. */
private class Rejected(val path: JsonPath, override val message: String) : RuntimeException(message)

private fun reject(path: JsonPath, message: String): Nothing = throw Rejected(path, message)

// ------------------------------------------------------------------ reading

/** The `oneOf` branches that say something other than "or null". */
private fun JsonObj.branches(): List<JsonObj> =
    arr("oneOf").filterIsInstance<JsonObj>().filterNot { it["type"] == JsonStr("null") }

/** The component this branch points at, or null where the document wrote it out here. */
private fun JsonObj.reference(): String? = str("\$ref")?.substringAfterLast('/')

/**
 * The value this schema declares for [property]: `kind: { const: card }`, or the
 * single-valued `enum` saying the same. Read again rather than shared with
 * `pelican-codegen`, which asks what an unmapped branch is already called where
 * this asks what to write in a mapping that does not exist yet.
 */
private fun JsonObj.constant(property: String): String? {
    val declared = obj("properties")?.obj(property) ?: return null
    return declared.str("const") ?: (declared.arr("enum").singleOrNull() as? JsonStr)?.value
}

private fun JsonValue?.step(key: String): JsonValue? = when (this) {
    is JsonObj -> this[key]
    is JsonArr -> key.toIntOrNull()?.let { items.getOrNull(it) }
    else -> null
}

/** [root] with [value] put where [steps] leads. The path is known to resolve by now. */
private fun replaced(root: JsonValue, steps: List<String>, value: JsonObj): JsonValue {
    if (steps.isEmpty()) return value
    val key = steps.first()
    val rest = steps.drop(1)
    return when (root) {
        is JsonObj -> root.with(key, replaced(root[key] ?: JsonObj(emptyMap()), rest, value))

        is JsonArr -> JsonArr(
            root.items.mapIndexed { i, item -> if (i == key.toIntOrNull()) replaced(item, rest, value) else item },
        )

        else -> root
    }
}

/** Every `discriminator` anywhere under [node], by identity. [seen] stops a cycle. */
private fun collectDiscriminators(node: JsonValue?, found: MutableSet<JsonObj>, seen: MutableSet<JsonValue>) {
    when (node) {
        is JsonObj -> {
            if (!seen.add(node)) return
            (node["discriminator"] as? JsonObj)?.let { found += it }
            node.fields.values.forEach { collectDiscriminators(it, found, seen) }
        }

        is JsonArr -> node.items.forEach { collectDiscriminators(it, found, seen) }

        else -> Unit
    }
}

// ------------------------------------------------------------- what to say

private const val NOTHING_THERE =
    "There is no schema at that position. A hint addresses a component by name — `Payment` — a schema " +
        "inside one — `Order/properties/payment` — or a JSON pointer from the root of the document, " +
        "written `#/paths/~1payments/post/...` with RFC 6901 escaping."

private const val UNREACHED =
    "Nothing generated from this document reaches that schema, so the hint states a fact about nothing. " +
        "Either the operations that used it are excluded, or the document no longer references it. Drop " +
        "the hint: a claim about a payload format that is checked against nothing is exactly the silent " +
        "weakening a strict import is for."

private fun notAUnion(schema: JsonObj): String {
    val what = when {
        schema["oneOf"] != null -> "a `oneOf` of one shape, which is that shape"

        schema["anyOf"] != null ->
            "an `anyOf`, which stays refused: a payload may satisfy two of its branches at once, and a " +
                "sealed hierarchy would say something narrower than the document does"

        schema["allOf"] != null -> "an `allOf`, which is merged into the one class it describes"

        else -> "a schema with no `oneOf` in it"
    }
    return "A hint names the property that tells a union's branches apart, and there is no union here: " +
        "this is $what."
}

private fun stated(schema: JsonObj): String {
    val property = (schema["discriminator"] as? JsonObj)?.str("propertyName")
    return "The document states its own `discriminator`${property?.let { " (`propertyName: $it`)" }.orEmpty()}, " +
        "so the hint is no longer needed. Two opinions about one union is one too many and the " +
        "document's is the one that is read — drop the hint."
}

private fun undeclared(property: String, branches: List<JsonObj>): String {
    val declared = branches.flatMap { it.obj("properties")?.fields?.keys.orEmpty() }.distinct()
    val offered = if (declared.isEmpty()) {
        "no branch declares any properties at all"
    } else {
        "between them the branches declare ${declared.joinToString(", ") { "`$it`" }}"
    }
    return "No branch of this union declares `$property`, and $offered. A hint supplies what the document " +
        "left out; it cannot supply a property the document never mentions, because the generated types " +
        "would then describe a payload nothing in the document does. Check the spelling, or declare the " +
        "property on the branches."
}

private fun unselectable(property: String): String =
    "This branch is written inline and declares no `$property`, so nothing says which payloads are it. A " +
        "branch that is a reference falls back to the name of the schema it points at — that is the name " +
        "the document gives it — and an inline branch has no name to fall back to. Inventing one would " +
        "generate a client sending a value the service never accepts. Give it a `const` for `$property`, " +
        "or point it at a named schema."

private fun repeatedValues(property: String, values: Collection<String>): String =
    "Two branches of this union are selected by the same `$property`: " +
        "${values.joinToString(", ") { "`$it`" }}. One value cannot place a payload in two classes. The " +
        "value is a branch's `const` for `$property` where it declares one, and the name of the schema it " +
        "points at where it does not."
