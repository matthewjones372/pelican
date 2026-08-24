package dev.pelican.importer

import dev.pelican.JsonArr
import dev.pelican.JsonBool
import dev.pelican.JsonNum
import dev.pelican.JsonObj
import dev.pelican.JsonStr
import dev.pelican.JsonValue

/*
 * Reading a JSON tree, without pretending it is typed.
 *
 * A document read off disk is a tree of maybes: every field may be absent, and
 * every field may be the wrong kind of thing, because it was written by hand or
 * by another generator. These accessors answer "what does it say here" and
 * nothing else — an absent field and a field of the wrong type both come back
 * null, and the caller decides which of those is a problem and what to say
 * about it.
 */

internal fun JsonObj.obj(key: String): JsonObj? = this[key] as? JsonObj

internal fun JsonObj.arr(key: String): List<JsonValue> = (this[key] as? JsonArr)?.items.orEmpty()

internal fun JsonObj.str(key: String): String? = (this[key] as? JsonStr)?.value

internal fun JsonObj.bool(key: String): Boolean = (this[key] as? JsonBool)?.value == true

internal fun JsonObj.int(key: String): Int? = (this[key] as? JsonNum)?.value?.toInt()

internal fun JsonObj.strings(key: String): List<String> = arr(key).mapNotNull { (it as? JsonStr)?.value }

/** The object fields, in document order, or nothing when this is not an object. */
internal fun JsonValue?.entries(): List<Pair<String, JsonValue>> =
    (this as? JsonObj)?.fields?.map { it.key to it.value }.orEmpty()

internal fun JsonObj.without(key: String): JsonObj = JsonObj(fields - key)

internal fun JsonObj.with(key: String, value: JsonValue): JsonObj = JsonObj(fields + (key to value))

internal fun jsonObjOf(vararg pairs: Pair<String, JsonValue>): JsonObj = JsonObj(pairs.toMap())

/** Name -> description, as OpenAPI models a scheme's scopes. */
internal fun JsonObj.stringMap(): Map<String, String> =
    fields.mapValues { (_, value) -> (value as? JsonStr)?.value.orEmpty() }
