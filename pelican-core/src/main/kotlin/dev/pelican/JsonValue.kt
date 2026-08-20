package dev.pelican

/**
 * A minimal JSON tree.
 *
 * Core needs to *represent* schemas, but it should not have an opinion about
 * which library *produces* JSON — that is exactly the thing being made
 * pluggable. Sixty lines here is the price of `pelican-core` having no
 * third-party dependencies at all.
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
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}

data class JsonNum(val value: Number) : JsonValue {
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
 * Deliberately not a general-purpose parser and not offered as one: the
 * configured [Codecs] is what reads request and response bodies, and adding a
 * second JSON library to core would be exactly the coupling this module
 * exists to avoid. This is here because a form body has to be turned into the
 * fields it travels as, and the only description of a value core can get from
 * a `BodyCodec` is the JSON that codec produced — so something has to read it
 * back. See [formCodec].
 */
fun parseJson(text: String): JsonValue {
    val reader = JsonReader(text)
    val value = reader.value()
    reader.skipWhitespace()
    require(reader.finished) { "Trailing content after the JSON value at offset ${reader.offset}" }
    return value
}

private class JsonReader(private val text: String) {
    var offset = 0
        private set

    val finished: Boolean get() = offset >= text.length

    fun skipWhitespace() {
        while (offset < text.length && text[offset].isWhitespace()) offset++
    }

    fun value(): JsonValue {
        skipWhitespace()
        require(!finished) { "Unexpected end of JSON" }
        return when (val c = text[offset]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> JsonStr(string())
            't' -> literal("true", JsonBool(true))
            'f' -> literal("false", JsonBool(false))
            'n' -> literal("null", JsonNull)
            else -> if (c == '-' || c.isDigit()) number() else error("Unexpected '$c' at offset $offset")
        }
    }

    private fun obj(): JsonObj {
        offset++ // '{'
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') { offset++; return JsonObj(fields) }
        while (true) {
            skipWhitespace()
            val name = string()
            skipWhitespace()
            expect(':')
            fields[name] = value()
            skipWhitespace()
            when (val c = take()) {
                ',' -> Unit
                '}' -> return JsonObj(fields)
                else -> error("Expected ',' or '}' but found '$c' at offset ${offset - 1}")
            }
        }
    }

    private fun arr(): JsonArr {
        offset++ // '['
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (peek() == ']') { offset++; return JsonArr(items) }
        while (true) {
            items += value()
            skipWhitespace()
            when (val c = take()) {
                ',' -> Unit
                ']' -> return JsonArr(items)
                else -> error("Expected ',' or ']' but found '$c' at offset ${offset - 1}")
            }
        }
    }

    private fun string(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            when (val c = take()) {
                '"' -> return sb.toString()
                '\\' -> when (val escape = take()) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        require(offset + 4 <= text.length) { "Truncated \\u escape at offset $offset" }
                        sb.append(text.substring(offset, offset + 4).toInt(16).toChar())
                        offset += 4
                    }

                    else -> error("Unknown escape '\\$escape' at offset ${offset - 1}")
                }

                else -> sb.append(c)
            }
        }
    }

    private fun number(): JsonNum {
        val start = offset
        if (peek() == '-') offset++
        while (offset < text.length && (text[offset].isDigit() || text[offset] in ".eE+-")) offset++
        val raw = text.substring(start, offset)
        // A whole number stays whole: rendering 1.0 back into a form field
        // where the sender wrote 1 would be a round trip that changed the value.
        val number: Number = raw.toLongOrNull() ?: raw.toDoubleOrNull()
            ?: error("'$raw' is not a number, at offset $start")
        return JsonNum(number)
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        require(text.startsWith(word, offset)) { "Unexpected content at offset $offset" }
        offset += word.length
        return value
    }

    private fun peek(): Char? = if (finished) null else text[offset]

    private fun take(): Char {
        require(!finished) { "Unexpected end of JSON" }
        return text[offset++]
    }

    private fun expect(c: Char) {
        val actual = take()
        require(actual == c) { "Expected '$c' but found '$actual' at offset ${offset - 1}" }
    }
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
