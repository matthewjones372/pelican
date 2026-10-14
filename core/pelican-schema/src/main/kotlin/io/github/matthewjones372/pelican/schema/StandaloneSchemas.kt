package io.github.matthewjones372.pelican.schema

import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.jsonObj
import kotlin.reflect.KType

/**
 * A type as a JSON Schema 2020-12 document that resolves on its own: a pointer
 * at the root, and every schema it reaches under `$defs` beside it.
 *
 * What a [SchemaSource] publishes is a fragment of an OpenAPI document, so its
 * pointers resolve only where that document is. Anything else holding one — a
 * validator, a registry of its own — gets them addressed to where they are.
 */
class StandaloneSchemas(private val schemas: SchemaSource) {

    /** [type] described, with nothing outside the returned object left to resolve. */
    fun schema(type: KType): JsonObj {
        val defs = SchemaDefs()
        val root = schemas.schema(type, defs).rebasedOnto(defs)
        val definitions = defs.all().rebasedOnto(defs).branchesSelected()
        val document = if (definitions.isEmpty) root else root + jsonObj { put(DEFS, definitions) }
        document.refuseOpenHierarchies()
        return document
    }
}

/** Where a standalone document's schemas accumulate, and the `#/$defs/` each is reachable at. */
internal class SchemaDefs : SchemaComponents {
    private val definitions = LinkedHashMap<String, JsonObj>()

    override fun register(name: String, schema: JsonObj) { definitions[name] = schema }
    override fun isRegistered(name: String) = name in definitions
    override fun ref(name: String) = jsonObj { REF to DEFS_POINTER + name }

    fun all(): JsonObj = JsonObj(definitions.toMap())
}

/**
 * The same schema with every pointer addressed to where [components] puts
 * things.
 *
 * Only a pointer's last segment is read, so this needs no opinion about the
 * prefix it replaces: a source that asked the `SchemaComponents` it was handed
 * already wrote the right one and comes back unchanged, and `pelican-jackson`
 * writes `#/components/schemas/` whatever it was handed.
 */
internal fun JsonObj.rebasedOnto(components: SchemaComponents): JsonObj =
    JsonObj(fields.mapValues { (key, value) -> components.rebase(key, value) })

private fun SchemaComponents.rebase(key: String, value: JsonValue): JsonValue = when {
    key == REF && value is JsonStr -> JsonStr(pointerTo(value.value))

    // A mapping's values are bare pointer strings rather than `$ref` objects,
    // so the walk below would carry them straight past.
    key == DISCRIMINATOR && value is JsonObj -> value.mappingRebased(this)

    value is JsonObj -> value.rebasedOnto(this)

    value is JsonArr -> JsonArr(value.items.map { rebase(key, it) })

    else -> value
}

private fun JsonObj.mappingRebased(components: SchemaComponents): JsonObj {
    val mapping = this[MAPPING] as? JsonObj ?: return this
    return this + jsonObj {
        put(MAPPING, JsonObj(mapping.fields.mapValues { (_, value) -> components.rebase(REF, value) }))
    }
}

/**
 * Where [components] keeps the schema this names. A value with no `/` in it is a
 * bare schema name rather than a reference — which is what a `discriminator`
 * mapping falls back to — and stays as it is.
 */
private fun SchemaComponents.pointerTo(pointer: String): String {
    if ('/' !in pointer) return pointer
    return (ref(pointer.substringAfterLast('/'))[REF] as? JsonStr)?.value ?: pointer
}

private const val REF = "\$ref"
private const val DEFS = "\$defs"
private const val DEFS_POINTER = "#/\$defs/"
private const val DISCRIMINATOR = "discriminator"
private const val MAPPING = "mapping"
