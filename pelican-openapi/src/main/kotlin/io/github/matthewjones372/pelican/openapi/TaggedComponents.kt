package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.ErrorSpec
import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.SchemaRegistry
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.jsonObj

private const val COMPONENT_PREFIX = "#/components/schemas/"

/**
 * Writes each tagged failure's tag into its component, as a required `const`, because that is what
 * the server sends and what OpenAPI asks of a discriminator: the property it names has to be in
 * every schema the mapping points at. See spec 0066.
 *
 * A component is one schema for every use of its type, so a type used under two tags, or tagged in
 * one place and plain in another, has no single schema that is true. That is refused here, after
 * [rendered] is complete, because only then is every `$ref` to the component known.
 */
internal fun declareTags(
    failures: List<ErrorSpec>,
    schemas: SchemaSource,
    components: SchemaRegistry,
    rendered: List<JsonValue>,
) {
    val tagged = failures.mapNotNull { err ->
        val type = err.type ?: return@mapNotNull null
        val tag = err.tag ?: return@mapNotNull null
        val ref = (schemas.schema(type, components)["\$ref"] as? JsonStr)?.value ?: return@mapNotNull null
        ref.removePrefix(COMPONENT_PREFIX) to (tag to err.discriminator)
    }
    tagged.groupBy({ it.first }, { it.second }).forEach { (name, uses) ->
        val tags = uses.distinct()
        require(tags.size == 1) {
            "$name is tagged ${tags.joinToString(" and ") { (tag, field) -> "`$tag` under `$field`" }}. " +
                "Its schema is one component, and a tag is written into it, so it can only carry one. " +
                "Declare a second type for the second failure."
        }
        val refs = rendered.sumOf { refsTo("$COMPONENT_PREFIX$name", it) }
        require(refs == uses.size) {
            "$name is tagged `${tags.single().first}` as a failure, and also used where it is not tagged. " +
                "Its schema is one component, and a tag is written into it, so the untagged use would be " +
                "documented as carrying a tag it never sends. Declare a second type for one of them."
        }
        val (tag, field) = tags.single()
        val schema = components.all()[name] as JsonObj
        components.register(name, withTag(name, schema, tag, field))
    }
}

private fun withTag(name: String, schema: JsonObj, tag: String, field: String): JsonObj {
    val properties = (schema["properties"] as? JsonObj)?.fields.orEmpty()
    require(field !in properties) {
        "$name already has a field `$field`, and its tag is written under that name. Tag it under " +
            "another with tagged(tag, field = \"...\")."
    }
    val required = (schema["required"] as? JsonArr)?.items.orEmpty()
    val declared = jsonObj {
        "type" to "string"
        "const" to tag
    }
    // First, so a reader of the schema meets the field the whole response is told apart by.
    return schema + jsonObj {
        put("properties", JsonObj(mapOf(Pair(field, declared)) + properties))
        put("required", JsonArr(listOf(JsonStr(field)) + required))
    }
}

/** How many `$ref`s in [value] point at [ref]. A discriminator's mapping names it too, but as a value. */
private fun refsTo(ref: String, value: JsonValue): Int = when (value) {
    is JsonObj -> value.fields.entries.sumOf { (key, v) ->
        if (key == "\$ref" && (v as? JsonStr)?.value == ref) 1 else refsTo(ref, v)
    }

    is JsonArr -> value.items.sumOf { refsTo(ref, it) }

    else -> 0
}
