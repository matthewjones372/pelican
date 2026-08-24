package dev.pelican.importer

import dev.pelican.JsonArr
import dev.pelican.JsonObj
import dev.pelican.JsonStr
import dev.pelican.JsonValue

/**
 * What a schema has to say to become a Kotlin type.
 *
 * The type generator this hands schemas to — `pelican-codegen`'s, the same one
 * the client generator uses — answers anything it cannot model with `Any?`.
 * That is the right answer *there*: a client that generates is better than a
 * client that does not, and `Any?` is honest about what it knows.
 *
 * It is the wrong answer here. An import that turned a `oneOf` into `Any?`
 * would produce a handler taking `Any?`, a document that no longer says what
 * the original said, and no sign that anything was lost. So the shapes that
 * would degrade are refused before the generator ever sees them, and the ones
 * that are genuinely unconstrained — an empty schema, a free-form object — are
 * let through, because `Any?` and `Map<String, Any?>` are what those mean.
 */
internal object Schemas {

    fun check(schema: JsonValue?, path: JsonPath) {
        val obj = schema as? JsonObj ?: return

        when {
            obj["oneOf"] != null -> unsupported(
                path / "oneOf",
                "A value that is one of several shapes. Kotlin can hold that as a sealed hierarchy, " +
                    "and nothing in the document says which name each branch should have — so this is " +
                    "one to write by hand and describe with a schema of your own.",
            )

            obj["not"] != null -> unsupported(path / "not", "A schema defined by what it excludes.")

            obj["discriminator"] != null -> unsupported(
                path / "discriminator",
                "A discriminated union, which needs the sealed hierarchy a `oneOf` would.",
            )

            obj.arr("allOf").size > 1 -> unsupported(
                path / "allOf",
                "Several schemas merged into one. Merging them here would invent a type the document " +
                    "never named; declare the merged shape in the document instead.",
            )

            obj.branches().size > 1 -> unsupported(
                path / "anyOf",
                "A value that is any of several shapes, which is a union by another name.",
            )

            obj.types().size > 1 -> unsupported(
                path / "type",
                "A value that is ${obj.types().joinToString(" or ")}, and a Kotlin property is one of them.",
            )
        }

        nestedIn(obj).forEach { (key, nested) -> check(nested, path / key) }
    }

    /** Every named schema [roots] reach, directly or through another. */
    fun reachable(roots: List<JsonValue?>, components: JsonObj): Set<String> {
        val found = LinkedHashSet<String>()
        val pending = ArrayDeque<JsonValue>()
        roots.filterNotNull().forEach { pending += it }

        while (pending.isNotEmpty()) {
            val here = pending.removeFirst()
            val obj = here as? JsonObj ?: continue
            (obj["\$ref"] as? JsonStr)?.value?.substringAfterLast('/')?.let { name ->
                if (found.add(name)) components[name]?.let { pending += it }
            }
            nestedIn(obj).forEach { (_, nested) -> pending += nested }
        }
        return found
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

    /** The `anyOf` branches that say something other than "or null". */
    private fun JsonObj.branches(): List<JsonValue> =
        arr("anyOf").filterNot { (it as? JsonObj)?.str("type") == "null" }

    /** The types this schema claims, ignoring the `"null"` that only widens one. */
    private fun JsonObj.types(): List<String> = when (val type = this["type"]) {
        is JsonStr -> listOf(type.value)
        is JsonArr -> type.items.mapNotNull { (it as? JsonStr)?.value }.filterNot { it == "null" }
        else -> emptyList()
    }
}
