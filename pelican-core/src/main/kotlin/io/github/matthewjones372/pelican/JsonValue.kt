package io.github.matthewjones372.pelican

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints

/**
 * A minimal JSON tree. Core has to represent schemas without an opinion about
 * which library produces JSON — that being the pluggable part — and this is
 * the price of `pelican-core` having no third-party dependencies.
 */
sealed interface JsonValue {
    fun render(): String = StringBuilder().also { write(it) }.toString()
    fun write(sb: StringBuilder)
}

data class JsonObj(val fields: Map<String, JsonValue>) : JsonValue {
    override fun write(sb: StringBuilder) {
        sb.append('{')
        var first = true
        fields.forEach { (k, v) ->
            if (!first) sb.append(',')
            first = false
            JsonStr(k).write(sb)
            sb.append(':')
            v.write(sb)
        }
        sb.append('}')
    }

    operator fun get(key: String): JsonValue? = fields[key]
    operator fun plus(other: JsonObj) = JsonObj(fields + other.fields)
    val isEmpty: Boolean get() = fields.isEmpty()
}

data class JsonArr(val items: List<JsonValue>) : JsonValue {
    override fun write(sb: StringBuilder) {
        sb.append('[')
        items.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            v.write(sb)
        }
        sb.append(']')
    }

    val isEmpty: Boolean get() = items.isEmpty()
}

data class JsonStr(val value: String) : JsonValue {
    override fun write(sb: StringBuilder) {
        sb.append('"')
        for (c in value) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(HEX).padStart(UNICODE_ESCAPE_DIGITS, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}

data class JsonNum(val value: Number) : JsonValue {
    /**
     * The JSON grammar has no NaN and no infinity, so a tree holding one could
     * be written and never read back. Refused here rather than at `write`,
     * because the mistake is made where the value is built and a render-time
     * throw would surface it inside an interpreter instead.
     */
    init {
        val finite = when (value) {
            is Double -> value.isFinite()
            is Float -> value.isFinite()
            else -> true
        }
        require(finite) { "$value cannot be written as JSON: the grammar has no NaN and no infinity." }
    }

    override fun write(sb: StringBuilder) { sb.append(value.toString()) }
}

data class JsonBool(val value: Boolean) : JsonValue {
    override fun write(sb: StringBuilder) { sb.append(if (value) "true" else "false") }
}

data object JsonNull : JsonValue {
    override fun write(sb: StringBuilder) { sb.append("null") }
}

// ------------------------------------------------------------------ builders

class JsonObjBuilder internal constructor() {
    private val fields = LinkedHashMap<String, JsonValue>()

    infix fun String.to(value: JsonValue) { fields[this] = value }
    infix fun String.to(value: String) { fields[this] = JsonStr(value) }
    infix fun String.to(value: Number) { fields[this] = JsonNum(value) }
    infix fun String.to(value: Boolean) { fields[this] = JsonBool(value) }

    fun put(key: String, value: JsonValue?) { if (value != null) fields[key] = value }
    fun putIfNotNull(key: String, value: String?) { if (value != null) fields[key] = JsonStr(value) }

    internal fun build() = JsonObj(fields)
}

fun jsonObj(block: JsonObjBuilder.() -> Unit): JsonObj = JsonObjBuilder().apply(block).build()

/** The empty object, for defaults that would otherwise allocate one per call. */
val emptyJsonObj = JsonObj(emptyMap())

fun jsonArr(values: List<JsonValue>): JsonArr = JsonArr(values)

fun jsonStrings(values: List<String>): JsonArr = JsonArr(values.map { JsonStr(it) })

// ------------------------------------------------------------------- reading

/**
 * Reads a JSON document into the tree above.
 *
 * Not a general-purpose entry point: a request body goes through the configured
 * [Codecs], and [CodecFactory.readTree] is how anything holding one reads a
 * document. This is what is left — a generated client parsing the document
 * embedded in it, and the golden and compatibility tools reading documents this
 * project did not write — and the default `readTree` falls back to it.
 *
 * Throws [IllegalArgumentException] for anything it will not accept, depth
 * included.
 */
fun parseJson(text: String): JsonValue =
    try {
        jsonFactory.createParser(text).use { parser ->
            requireNotNull(parser.nextToken()) { "Unexpected end of JSON" }
            val value = parser.readValue()
            require(parser.nextToken() == null) {
                "Trailing content after the JSON value, at ${parser.currentLocation().offsetDescription()}"
            }
            value
        }
    } catch (e: JacksonException) {
        throw IllegalArgumentException(e.originalMessage ?: "That is not JSON", e)
    }

/**
 * Bounded where the reader itself is, rather than in a check of our own: a
 * document deeper than this is refused before the recursion below reaches it,
 * which is what makes reading a message from the network safe. Sixty-four is
 * six times the deepest document in this repository; Jackson's own default of
 * a thousand is a stack's worth of frames, not a document's.
 */
private val jsonFactory: JsonFactory = JsonFactory.builder()
    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH).build())
    .build()

private fun JsonParser.readValue(): JsonValue = when (currentToken()) {
    JsonToken.START_OBJECT -> readObject()
    JsonToken.START_ARRAY -> readArray()
    JsonToken.VALUE_STRING -> JsonStr(text)
    JsonToken.VALUE_NUMBER_INT -> JsonNum(readInteger())
    JsonToken.VALUE_NUMBER_FLOAT -> JsonNum(doubleValue)
    JsonToken.VALUE_TRUE -> JsonBool(true)
    JsonToken.VALUE_FALSE -> JsonBool(false)
    JsonToken.VALUE_NULL -> JsonNull
    else -> throw IllegalArgumentException("Unexpected $currentToken at ${currentLocation().offsetDescription()}")
}

/**
 * `Long` first, so a whole number stays whole: rendering 1.0 back into a form
 * field where the sender wrote 1 would be a round trip that changed the value.
 * Past `Long`, the digits are kept rather than rounded into a `Double`.
 */
private fun JsonParser.readInteger(): Number =
    if (numberType == JsonParser.NumberType.BIG_INTEGER) bigIntegerValue else longValue

private fun JsonParser.readObject(): JsonObj {
    val fields = LinkedHashMap<String, JsonValue>()
    while (nextToken() != JsonToken.END_OBJECT) {
        val name = currentName()
        nextToken()
        fields[name] = readValue()
    }
    return JsonObj(fields)
}

private fun JsonParser.readArray(): JsonArr {
    val items = mutableListOf<JsonValue>()
    while (nextToken() != JsonToken.END_ARRAY) items += readValue()
    return JsonArr(items)
}

/** Pretty-prints with two-space indentation. Only used for the served spec. */
fun JsonValue.renderPretty(indent: String = ""): String {
    val next = "$indent  "
    return when (this) {
        is JsonObj ->
            if (fields.isEmpty()) "{}"
            else fields.entries.joinToString(",\n", "{\n", "\n$indent}") { (k, v) ->
                "$next${JsonStr(k).render()}: ${v.renderPretty(next)}"
            }

        is JsonArr ->
            if (items.isEmpty()) "[]"
            else items.joinToString(",\n", "[\n", "\n$indent]") { "$next${it.renderPretty(next)}" }

        else -> render()
    }
}

/** `\uXXXX` is four hex digits, by the JSON grammar. */
private const val UNICODE_ESCAPE_DIGITS = 4
private const val HEX = 16

/** See [jsonFactory]. */
private const val MAX_DEPTH = 64
