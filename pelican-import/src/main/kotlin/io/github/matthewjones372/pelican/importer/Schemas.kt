package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.codegen.Composed
import io.github.matthewjones372.pelican.codegen.composed

/**
 * What a schema has to say to become a Kotlin type.
 *
 * The type generator this hands schemas to — `pelican-codegen`'s, the same one
 * the client generator uses — answers anything it cannot model with `Any?`.
 * That is the right answer *there*: a client that generates is better than a
 * client that does not, and `Any?` is honest about what it knows.
 *
 * It is the wrong answer here. An import that turned a union into `Any?` would
 * produce a handler taking `Any?`, a document that no longer says what the
 * original said, and no sign that anything was lost. So the shapes that would
 * degrade are refused before the generator ever sees them, and the ones that
 * are genuinely unconstrained — an empty schema, a free-form object — are let
 * through, because `Any?` and `Map<String, Any?>` are what those mean.
 *
 * The composed shapes are read by `pelican-codegen` rather than here, and by
 * exactly the function that generates them. Two readings of one `oneOf` is one
 * reading too many: the day they disagreed, this would accept a document the
 * generator then quietly degraded, which is the failure mode the whole module
 * is arranged against.
 */
internal object Schemas {

    fun check(schema: JsonValue?, path: JsonPath, components: JsonObj, name: String? = null) {
        val obj = schema as? JsonObj ?: return

        when {
            obj["not"] != null -> unsupported(path / "not", "A schema defined by what it excludes.")

            obj.types().size > 1 -> unsupported(
                path / "type",
                "A value that is ${obj.types().joinToString(" or ")}, and a Kotlin property is one of them.",
            )

            else -> (composed(obj, components, name) as? Composed.Undescribable)
                ?.let { unsupported(path / it.keyword, it.reason + wayThrough(obj, it)) }
        }

        nestedIn(obj).forEach { (key, nested) -> check(nested, path / key, components) }
    }

    /**
     * The one refusal here with a way through that is not "lose the
     * operation", said where the refusal is.
     *
     * It belongs to this module rather than to the reason `pelican-codegen`
     * wrote, because it is a sentence about a build file: the generator has no
     * opinion about `endpoints { }`, and a document a *client* was generated
     * from was never refused in the first place.
     */
    private fun wayThrough(obj: JsonObj, undescribable: Composed.Undescribable): String =
        if (undescribable.keyword != "oneOf" || obj["discriminator"] != null) {
            ""
        } else {
            "\n\nWhere the document is not yours to change, state the property in the build file " +
                "instead — `discriminator(\"Payment\", property = \"kind\")` in the `endpoints { }` " +
                "entry, addressing the schema by component name or by JSON pointer."
        }

    /** Every named schema [roots] reach, directly or through another. */
    fun reachable(roots: List<JsonValue?>, components: JsonObj): Set<String> {
        val found = LinkedHashSet<String>()
        val pending = ArrayDeque<JsonValue>()
        roots.filterNotNull().forEach { pending += it }

        while (pending.isNotEmpty()) {
            val obj = pending.removeFirst() as? JsonObj ?: continue
            (obj["\$ref"] as? JsonStr)?.value?.substringAfterLast('/')
                ?.let { follow(it, components, found, pending) }
            nestedIn(obj).forEach { (_, nested) -> pending += nested }
        }
        return found
    }

    /**
     * One name, and the branches of the hierarchy it turns out to be.
     *
     * A hierarchy written the way 3.0 wrote one points upwards only: each
     * branch references the parent and the parent references nothing. Reached
     * through the parent, the branches would be dropped as unused, and the
     * sealed interface generated from it would have no classes under it.
     */
    private fun follow(
        name: String,
        components: JsonObj,
        found: MutableSet<String>,
        pending: ArrayDeque<JsonValue>,
    ) {
        if (!found.add(name)) return
        components[name]?.let { pending += it }
        branchesOf(name, components).forEach { follow(it, components, found, pending) }
    }

    private fun branchesOf(name: String, components: JsonObj): List<String> {
        val schema = components[name] as? JsonObj ?: return emptyList()
        val union = composed(schema, components, name) as? Composed.Union ?: return emptyList()
        return union.branches.mapNotNull { it.ref }
    }

    /** The schemas inside this one, with the key each sat under for the path. */
    private fun nestedIn(obj: JsonObj): List<Pair<String, JsonValue>> = buildList {
        obj.obj("properties").entries().forEach { (name, nested) -> add("properties.$name" to nested) }
        listOf("items", "additionalProperties", "not").forEach { key ->
            (obj[key] as? JsonObj)?.let { add(key to it) }
        }
        listOf("allOf", "anyOf", "oneOf").forEach { key ->
            obj.arr(key).forEachIndexed { i, nested -> add("$key[$i]" to nested) }
        }
    }

    /** The types this schema claims, ignoring the `"null"` that only widens one. */
    private fun JsonObj.types(): List<String> = when (val type = this["type"]) {
        is JsonStr -> listOf(type.value)
        is JsonArr -> type.items.mapNotNull { (it as? JsonStr)?.value }.filterNot { it == "null" }
        else -> emptyList()
    }
}
