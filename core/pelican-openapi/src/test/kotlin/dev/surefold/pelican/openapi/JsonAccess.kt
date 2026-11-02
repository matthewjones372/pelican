package dev.surefold.pelican.openapi

import dev.surefold.pelican.JsonArr
import dev.surefold.pelican.JsonBool
import dev.surefold.pelican.JsonNum
import dev.surefold.pelican.JsonObj
import dev.surefold.pelican.JsonStr
import dev.surefold.pelican.JsonValue
fun JsonValue?.obj(): JsonObj = this as? JsonObj ?: error("not an object: $this")
fun JsonValue?.arr(): List<JsonValue> = (this as? JsonArr)?.items ?: error("not an array: $this")
fun JsonValue?.str(): String = (this as? JsonStr)?.value ?: error("not a string: $this")
fun JsonValue?.bool(): Boolean = (this as? JsonBool)?.value ?: error("not a boolean: $this")
fun JsonValue?.num(): Number = (this as? JsonNum)?.value ?: error("not a number: $this")

/** Follows a path of object keys, e.g. `doc / "paths" / "/widgets" / "get"`. */
operator fun JsonValue?.div(key: String): JsonValue? = obj()[key]

fun JsonValue?.keys(): Set<String> = obj().fields.keys

fun JsonValue?.strings(): List<String> = arr().map { it.str() }

fun JsonValue.sortKeys(): JsonValue = when (this) {
    is JsonObj -> JsonObj(fields.toSortedMap().mapValues { (_, v) -> v.sortKeys() })
    is JsonArr -> JsonArr(items.map { it.sortKeys() })
    else -> this
}
