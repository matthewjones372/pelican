package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonBool
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue

/**
 * 3.0's spelling of a schema, rewritten as 3.1's.
 *
 * The disagreement that matters is nullability: 3.0 has `nullable: true` beside
 * the type, 3.1 has `"null"` among the types. Everything downstream reads the
 * 3.1 spelling, so this is where the older one stops existing.
 *
 * A nullable `$ref` makes it more than a rename: a reference has no `type` to
 * widen, so 3.1 spells it as an `anyOf` with a null branch and 3.0 could not
 * spell it at all.
 */
internal fun normaliseSchema(value: JsonValue): JsonObj {
    val schema = value as? JsonObj ?: return JsonObj(emptyMap())

    val nullable = schema.bool("nullable")
    var result = schema.without("nullable")

    result = JsonObj(
        result.fields.mapValues { (key, field) ->
            when {
                key in nested -> normaliseSchema(field)
                key in nestedMaps -> JsonObj(field.entries().associate { (n, s) -> n to normaliseSchema(s) })
                key in nestedLists -> JsonArr((field as? JsonArr)?.items.orEmpty().map { normaliseSchema(it) })
                else -> field
            }
        },
    )

    result = result.exclusiveBounds()
    if (!nullable) return result

    val declared = result["type"]
    return when {
        result["\$ref"] != null -> jsonObjOf("anyOf" to JsonArr(listOf(result, nullSchema)))

        declared is JsonStr -> result.with("type", JsonArr(listOf(declared, JsonStr("null"))))

        // Nullable with no type of its own says only "or null", and a schema
        // that says only that describes anything. Left as it stands rather
        // than given a type it never claimed.
        else -> result
    }
}

/**
 * 3.0 wrote `exclusiveMinimum: true` beside `minimum`; 3.1 writes the bound
 * itself. Nothing in the generated Kotlin depends on it — but the document a
 * generated service publishes is compared against the one it was imported
 * from, and a bound that changed shape on the way through would show up there
 * as a difference nobody made.
 */
private fun JsonObj.exclusiveBounds(): JsonObj {
    var result = this
    listOf("Minimum" to "minimum", "Maximum" to "maximum").forEach { (suffix, bound) ->
        val flag = "exclusive$suffix"
        if (this[flag] is JsonBool) {
            val value = this[bound]
            result = if (bool(flag) && value != null) {
                result.without(flag).without(bound).with(flag, value)
            } else {
                result.without(flag)
            }
        }
    }
    return result
}

private val nullSchema = jsonObjOf("type" to JsonStr("null"))

private val nested = setOf("items", "additionalProperties", "not")
private val nestedMaps = setOf("properties", "patternProperties")
private val nestedLists = setOf("allOf", "anyOf", "oneOf", "prefixItems")
