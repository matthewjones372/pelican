package dev.pelican.codegen

/*
 * Turning names written for the wire into names that are legal in Kotlin.
 *
 * A header called `X-Api-Key` cannot be a parameter name, so unlike the wire
 * names in the document these do get rewritten. The rule is mechanical and
 * stated once: strip everything that is not a letter or a digit, and camel-case
 * across what is left. `X-Api-Key` becomes `xApiKey`, never `apiKey` — a rule
 * that drops information is a rule that stops matching the day two headers
 * differ only by the part it dropped.
 */

private val keywords = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
    "try", "typealias", "typeof", "val", "var", "when", "while",
)

/** Names the generated method bodies already use for something else. */
private val locals = setOf("request", "response", "body", "reader", "codecs", "http", "base")

private val wordBoundary = Regex("[^A-Za-z0-9]+")

/** A parameter or property name: `X-Api-Key` -> `xApiKey`, `limit` -> `limit`. */
fun memberName(raw: String): String {
    val parts = raw.split(wordBoundary).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "value"
    val head = parts.first().replaceFirstChar(Char::lowercaseChar)
    val name = head + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    val legal = if (name.first().isDigit()) "_$name" else name
    return if (legal in keywords || legal in locals) "${legal}_" else legal
}

/** A type name: `order status` and `order-status` both become `OrderStatus`. */
fun typeName(raw: String): String {
    val parts = raw.split(wordBoundary).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "Value"
    val name = parts.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    return if (name.first().isDigit()) "_$name" else name
}

internal fun isIdentifier(value: String): Boolean =
    value.isNotEmpty() &&
        (value.first().isLetter() || value.first() == '_') &&
        value.all { it.isLetterOrDigit() || it == '_' } &&
        value !in keywords

internal fun kotlinString(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '$' -> append("\\$")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            else -> append(c)
        }
    }
    append('"')
}

/** A KDoc block, kept on one line when the text is one line. */
internal fun kdoc(text: String, indent: String): String {
    val lines = text.trim().lines().map { it.trim() }

    // `*/` inside the comment would end it early and break the file.
    fun safe(line: String) = line.replace("*/", "* /")
    return if (lines.size == 1) {
        "$indent/** ${safe(lines.single())} */"
    } else {
        lines.joinToString("\n", "$indent/**\n", "\n$indent */") {
            if (it.isEmpty()) "$indent *" else "$indent * ${safe(it)}"
        }
    }
}

internal fun indent(text: String, prefix: String): String =
    text.lines().joinToString("\n") { if (it.isBlank()) it else prefix + it }

/** Makes [name] unique against everything already [taken]. */
internal fun unique(name: String, taken: MutableSet<String>): String {
    var candidate = name
    var n = 2
    while (!taken.add(candidate)) {
        candidate = "$name$n"
        n++
    }
    return candidate
}
