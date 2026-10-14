package io.github.matthewjones372.pelican.schema

import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.jsonArr
import io.github.matthewjones372.pelican.jsonObj

/** The property and value one hierarchy uses to pick one branch. */
private data class Selector(val branch: String, val property: String, val value: String)

/**
 * OpenAPI's `discriminator` written where a JSON Schema validator can read it:
 * a `const` property on each branch, required, and the `discriminator` gone.
 *
 * The three codecs all synthesise that property when encoding and demand it
 * when decoding, and it belongs to no Kotlin type, so derivation has nothing to
 * emit — leaving a branch schema that a validator accepts and the codec that
 * described it then refuses.
 */
internal fun JsonObj.branchesSelected(): JsonObj {
    val selectors = fields.values.filterIsInstance<JsonObj>().flatMap { it.selectors() }
    refuseBranchesSelectedTwice(selectors)

    val byBranch = selectors.associateBy { it.branch }
    return JsonObj(
        fields.mapValues { (name, schema) ->
            if (schema !is JsonObj) schema
            else byBranch[name]?.let { schema.selectedBy(it) } ?: schema.withoutDiscriminator()
        },
    )
}

/** What this schema's `discriminator` says, or nothing where it is not a choice between branches. */
private fun JsonObj.selectors(): List<Selector> {
    if (this["oneOf"] !is JsonArr) return emptyList()
    val discriminator = this[DISCRIMINATOR] as? JsonObj ?: return emptyList()
    val property = (discriminator["propertyName"] as? JsonStr)?.value ?: return emptyList()
    val mapping = (discriminator["mapping"] as? JsonObj)?.fields.orEmpty()
    return mapping.mapNotNull { (value, pointer) ->
        (pointer as? JsonStr)?.let { Selector(it.value.substringAfterLast('/'), property, value) }
    }
}

/** The branch with the property that picks it, pinned to the value that picks it. */
private fun JsonObj.selectedBy(selector: Selector): JsonObj {
    val properties = (this["properties"] as? JsonObj) ?: JsonObj(emptyMap())
    val required = ((this["required"] as? JsonArr)?.items.orEmpty()).filterIsInstance<JsonStr>()
    return this + jsonObj {
        put("properties", properties + jsonObj { put(selector.property, jsonObj { "const" to selector.value }) })
        put("required", jsonArr((required - JsonStr(selector.property)) + JsonStr(selector.property)))
    }
}

/** 3.1 keeps `discriminator` beside `oneOf`; 2020-12 has no such keyword, and the branches now say it. */
private fun JsonObj.withoutDiscriminator(): JsonObj =
    if (DISCRIMINATOR in fields) JsonObj(fields - DISCRIMINATOR) else this

/**
 * A class in two hierarchies that disagree about how it is picked has no one
 * schema: it would need both properties, and a payload carrying both is one
 * neither codec writes.
 */
private fun refuseBranchesSelectedTwice(selectors: List<Selector>) {
    val clashes = selectors.distinct().groupBy { it.branch }.filterValues { it.size > 1 }
    require(clashes.isEmpty()) {
        clashes.entries.joinToString("; ") { (branch, ways) ->
            "$branch is a branch of two hierarchies that pick it differently — " +
                ways.joinToString(" and ") { "`${it.property}` as `${it.value}`" }
        } + ". A branch carries one selector, so give the hierarchies the same property and value, " +
            "or give each hierarchy a type of its own."
    }
}

/**
 * kotlinx.serialization's open polymorphism, which it describes as an object
 * with a `polymorphic:` note because its branches register at runtime.
 *
 * Read from that note rather than from a descriptor: this module holds no
 * codec, and what it is handed is the schema. A standalone document is
 * something a validator or a model acts on, so "an object, and good luck" is
 * worth refusing where a document that a human reads beside the code is not.
 */
internal fun JsonValue.refuseOpenHierarchies() {
    val open = openHierarchies()
    require(open.isEmpty()) {
        "${open.joinToString()} registers its subclasses at run time, so no closed schema of it is honest. " +
            "Make the hierarchy `sealed`, so its branches are known, or describe the property as one branch."
    }
}

private fun JsonValue.openHierarchies(): List<String> = when (this) {
    is JsonObj -> listOfNotNull(openHierarchy()) + fields.values.flatMap { it.openHierarchies() }
    is JsonArr -> items.flatMap { it.openHierarchies() }
    else -> emptyList()
}

/** What this schema says it is polymorphic over, or null where it says nothing of the sort. */
private fun JsonObj.openHierarchy(): String? =
    (this["description"] as? JsonStr)?.value?.substringAfter(OPEN_NOTE, "")?.takeIf { it.isNotEmpty() }

private const val DISCRIMINATOR = "discriminator"
private const val OPEN_NOTE = "polymorphic: "
