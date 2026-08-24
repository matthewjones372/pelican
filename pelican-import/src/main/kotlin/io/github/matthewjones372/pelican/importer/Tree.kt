package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonBool
import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue

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

internal fun JsonObj.long(key: String): Long? = (this[key] as? JsonNum)?.value?.toLong()

internal fun JsonObj.strings(key: String): List<String> = arr(key).mapNotNull { (it as? JsonStr)?.value }

/**
 * The type this schema claims, ignoring the `"null"` that only widens one.
 *
 * A schema saying two real types is a union, and `Schemas.check` refuses it
 * before anything here has to decide which of them to believe.
 */
internal fun JsonObj.scalarType(): String? = str("type")
    ?: (this["type"] as? JsonArr)?.items?.mapNotNull { (it as? JsonStr)?.value }?.firstOrNull { it != "null" }

/** The object fields, in document order, or nothing when this is not an object. */
internal fun JsonValue?.entries(): List<Pair<String, JsonValue>> =
    (this as? JsonObj)?.fields?.map { it.key to it.value }.orEmpty()

internal fun JsonObj.without(key: String): JsonObj = JsonObj(fields - key)

internal fun JsonObj.with(key: String, value: JsonValue): JsonObj = JsonObj(fields + (key to value))

internal fun jsonObjOf(vararg pairs: Pair<String, JsonValue>): JsonObj = JsonObj(pairs.toMap())

/** Name -> description, as OpenAPI models a scheme's scopes. */
internal fun JsonObj.stringMap(): Map<String, String> =
    fields.mapValues { (_, value) -> (value as? JsonStr)?.value.orEmpty() }
