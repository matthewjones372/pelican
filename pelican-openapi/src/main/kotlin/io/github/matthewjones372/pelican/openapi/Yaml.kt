package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.*

/**
 * The same document, written as YAML.
 *
 * There is no YAML library here for the same reason there is no JSON one: this
 * module depends on `pelican-core` and nothing else, and core's [JsonValue] is
 * already the document. YAML is a second rendering of that tree, not a second
 * document — [openApi] runs once and both spellings come off the same result,
 * so the two cannot disagree about anything.
 *
 * What is emitted is YAML 1.2, which is a superset of JSON: block mappings and
 * block sequences, plain scalars where a plain scalar cannot be misread, and
 * double quotes (with JSON's own escapes, which YAML shares) where it could.
 * A string with newlines in it — a description, usually — is written as a
 * literal block so it reads as the paragraph it is.
 */
fun ApiSpec.openApiYaml(): String = openApi().renderYaml()

/**
 * Renders the tree as YAML. Public because the JSON counterpart is: something
 * holding a document it has already built should not have to rebuild it to
 * write it out the other way.
 */
fun JsonValue.renderYaml(): String = when (this) {
    is JsonObj -> if (isEmpty) "{}\n" else block(this, "")
    is JsonArr -> if (isEmpty) "[]\n" else block(this, "")
    else -> scalar(this) + "\n"
}

// --------------------------------------------------------------------------

/** A mapping or a sequence, as whole lines, every one of them at [indent]. */
private fun block(value: JsonValue, indent: String): String = when (value) {
    is JsonObj -> value.fields.entries.joinToString("") { (key, v) ->
        indent + plainOrQuoted(key) + ":" + tail(v, indent)
    }

    is JsonArr -> value.items.joinToString("") { item ->
        when {
            item.hasBlockForm -> {
                // The child is laid out two columns in, then the dash is
                // written over the indent of its first line.
                val child = block(item, "$indent  ")
                indent + "- " + child.substring(indent.length + 2)
            }

            else -> indent + "- " + inline(item) + "\n"
        }
    }

    else -> indent + scalar(value) + "\n"
}

/** What follows a `key:` — the value on the same line, or a block beneath it. */
private fun tail(value: JsonValue, indent: String): String = when {
    value.hasBlockForm -> "\n" + block(value, "$indent  ")
    value is JsonStr && isLiteralBlock(value.value) -> literalBlock(value.value, "$indent  ")
    else -> " " + inline(value) + "\n"
}

/** An empty collection is written where it stands; everything else nests. */
private val JsonValue.hasBlockForm: Boolean
    get() = when (this) {
        is JsonObj -> !isEmpty
        is JsonArr -> !isEmpty
        else -> false
    }

/** An empty collection has no block form; everything else here is a scalar. */
private fun inline(value: JsonValue): String = when (value) {
    is JsonObj -> "{}"
    is JsonArr -> "[]"
    else -> scalar(value)
}

private fun scalar(value: JsonValue): String = when (value) {
    is JsonStr -> plainOrQuoted(value.value)

    is JsonNum -> value.value.toString()

    is JsonBool -> if (value.value) "true" else "false"

    is JsonNull -> "null"

    // Only reachable through a caller that has already ruled these out.
    else -> value.render()
}

// ------------------------------------------------------------------- scalars

/**
 * A literal block keeps a description readable as the paragraph it was written
 * as, rather than as one long line with `\n` in it:
 *
 * ```yaml
 * description: |-
 *   The first line.
 *   The second.
 * ```
 *
 * The `-` chomps the trailing newline a block would otherwise add, which is
 * why a string that already ends in one is not written this way — it would
 * need a different chomping indicator to survive the round trip, and the
 * quoted form says the same thing without the special case.
 */
private fun isLiteralBlock(text: String): Boolean {
    if ('\n' !in text || text.endsWith('\n')) return false
    val lines = text.split('\n')
    // A first line starting with a space needs an explicit indentation
    // indicator; trailing spaces are eaten by any parser reading the block
    // back. Neither is worth spelling out when quoting is right there.
    if (lines.first().firstOrNull()?.isWhitespace() == true) return false
    return lines.none { it != it.trimEnd() } && text.none { it < ' ' && it != '\n' }
}

private fun literalBlock(text: String, indent: String): String =
    text.split('\n').joinToString("", " |-\n") { line ->
        if (line.isEmpty()) "\n" else "$indent$line\n"
    }

/**
 * Plain where a plain scalar reads back as the string it was, quoted where it
 * would not. The test is deliberately pessimistic: everything a YAML 1.2 core
 * schema resolves to some other type — `true`, `null`, `0x1f`, `.inf`, a
 * number — is quoted, as is anything carrying an indicator character in a
 * position where it means something. `1.0.0` stays plain; `1.0` does not.
 */
private fun plainOrQuoted(text: String): String =
    if (isPlainSafe(text)) text else JsonStr(text).render()

private val nonPlain = Regex("""^[-?:,\[\]{}#&*!|>'"%@`]|: |\s#|^\s|\s$""")

private val resolvesToAnotherType = Regex(
    """(?xi)
    ^(
        true|false|null|~|yes|no|on|off
      | [-+]?(\.[0-9]+|[0-9]+(\.[0-9]*)?)([e][-+]?[0-9]+)?
      | 0x[0-9a-f]+ | 0o[0-7]+
      | [-+]?\.(inf|nan)
      | [0-9]{4}-[0-9]{2}-[0-9]{2}.*
    )$
    """,
)

private fun isPlainSafe(text: String): Boolean =
    text.isNotEmpty() &&
        text.none { it < ' ' } &&
        !nonPlain.containsMatchIn(text) &&
        !resolvesToAnotherType.matches(text)
