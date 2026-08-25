package io.github.matthewjones372.pelican.codegen

/**
 * Turning names written for the wire into names that are legal in Kotlin.
 */

private val keywords = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
    "try", "typealias", "typeof", "val", "var", "when", "while",
)

/** Names the generated method bodies already use for something else. */
private val locals = setOf("request", "response", "body", "reader", "codecs", "http", "base")

private val wordBoundary = Regex("[^A-Za-z0-9]+")

/**
 * Whether a name can be written in Kotlin at all, in backticks if it has to
 * be. The JVM forbids these five characters in a member name, and no amount of
 * quoting gets them back.
 */
fun isWritable(value: String): Boolean =
    value.isNotEmpty() && value.none { it in ".;[]/<>" }

/** [value] as Kotlin writes it: bare where it is an identifier, in backticks where it is not. */
fun asWritten(value: String): String = if (isIdentifier(value)) value else "`$value`"

/** A parameter or property name: `X-Api-Key` -> `xApiKey`, `limit` -> `limit`. */
fun memberName(raw: String): String {
    val parts = raw.split(wordBoundary).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "value"
    val head = parts.first().replaceFirstChar(Char::lowercaseChar)
    val name = head + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    val legal = if (name.first().isDigit()) "_$name" else name
    return if (legal in keywords || legal in locals) "${legal}_" else legal
}

/**
 * What one branch of a union is called, in the order the document is willing
 * to say it.
 */
fun branchName(parent: String, index: Int, mapped: String?, ref: String?): String =
    typeName(mapped?.takeIf { it.readsAsAName() } ?: ref ?: "${parent}Variant${index + 1}")

private fun String.readsAsAName(): Boolean = none { it == '/' || it == '#' } && typeName(this) != "Value"

/** A type name: `order status` and `order-status` both become `OrderStatus`. */
fun typeName(raw: String): String {
    val parts = raw.split(wordBoundary).filter { it.isNotEmpty() }
    if (parts.isEmpty()) return "Value"
    val name = parts.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    return if (name.first().isDigit()) "_$name" else name
}

fun isIdentifier(value: String): Boolean =
    value.isNotEmpty() &&
        (value.first().isLetter() || value.first() == '_') &&
        value.all { it.isLetterOrDigit() || it == '_' } &&
        value !in keywords

fun kotlinString(value: String): String = buildString {
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
fun kdoc(text: String, indent: String): String {
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

fun indent(text: String, prefix: String): String =
    text.lines().joinToString("\n") { if (it.isBlank()) it else prefix + it }

/** Makes [name] unique against everything already [taken]. */
fun unique(name: String, taken: MutableSet<String>): String {
    var candidate = name
    var n = 2
    while (!taken.add(candidate)) {
        candidate = "$name$n"
        n++
    }
    return candidate
}
