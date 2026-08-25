package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.parseJson

/*
 * What the comparison looks like when somebody has to read it.
 *
 * A list of changes is the answer; a wall of it is not. What a developer needs
 * at the moment a build goes red is the count, the operations involved, and one
 * line each saying what a caller is about to be told — in that order, because
 * that is the order the decision is made in. Everything else is detail they can
 * scroll to.
 *
 * The layout is grouped by operation, indented, and quiet about the changes
 * nobody has to act on: those are counted at the end rather than listed, so the
 * breaking ones are what the eye lands on.
 */

/**
 * The changes, laid out for a terminal.
 *
 * ```
 *   3 changes break callers of Orders
 *
 *   POST /users/{userId}/orders
 *     ✖ the `currency` query parameter is new and required
 *         every caller that is not sending it is refused
 * ```
 *
 * Colour is used where the terminal has said it can take it — an interactive
 * console, or `FORCE_COLOR` — and never when `NO_COLOR` is set. A test report
 * or a CI log gets the same text without the escapes, which is why the layout
 * carries the meaning and the colour only underlines it.
 */
fun List<ApiChange>.report(heading: String, colour: Boolean = Ansi.available): String {
    if (isEmpty()) return "$heading — nothing changed."

    val ansi = if (colour) Ansi else Plain
    val breaking = filter { it.compatibility == Compatibility.BREAKING }
    val rest = size - breaking.size

    val headline = when {
        breaking.isEmpty() -> ansi.bold("$heading — ${count(size, "change")}, none of them breaking.")

        else -> ansi.red(
            ansi.bold(
                "$heading — ${count(breaking.size, "change")} " +
                    "${if (breaking.size == 1) "breaks" else "break"} callers.",
            ),
        )
    }

    val body = (breaking.ifEmpty { this }).groupBy { it.where }.entries.joinToString("\n\n") { (where, changes) ->
        "  " + ansi.bold(where) + "\n" + changes.joinToString("\n") { entry(it, ansi) }
    }

    val footer = when {
        breaking.isEmpty() -> ""
        rest == 0 -> ""
        else -> "\n\n  " + ansi.dim("and ${count(rest, "change")} nothing has to act on.")
    }

    return "$headline\n\n$body$footer"
}

/** The document that was published, read from the text a file or an endpoint gave. */
fun documentOf(json: String): JsonObj =
    parseJson(json) as? JsonObj ?: error("That is JSON, but it is not an OpenAPI document: it is not an object.")

/**
 * The comparison and its report in one call, over two documents as text.
 *
 * Written for a caller that reaches this by name rather than by type — the
 * Gradle plugin compiles against none of these modules and finds them on the
 * consumer's classpath, so what crosses that boundary is a `String`. See
 * [breakingChanges] for the other half of what such a caller needs.
 */
fun compatibilityReport(
    published: String,
    proposed: String,
    heading: String,
    colour: Boolean = Ansi.available,
): String = apiChanges(documentOf(published), documentOf(proposed)).report(heading, colour)

/** How many of the changes between two documents break somebody. Named for the same caller. */
fun breakingChanges(published: String, proposed: String): Int =
    apiChanges(documentOf(published), documentOf(proposed)).count { it.compatibility == Compatibility.BREAKING }

private fun entry(change: ApiChange, ansi: Style): String {
    val mark = when (change.compatibility) {
        Compatibility.BREAKING -> ansi.red(Marks.breaking)
        Compatibility.COMPATIBLE -> ansi.green(Marks.safe)
        Compatibility.COSMETIC -> ansi.dim(Marks.safe)
    }

    val consequence = if (change.consequence.isEmpty()) "" else "\n        " + ansi.dim(change.consequence)
    return "    $mark ${change.what}$consequence"
}

private fun count(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"

/** Whether the marks are drawn or spelled, which depends on what the console can render. */
private object Marks {
    private val unicode: Boolean = System.getProperty("file.encoding")
        .orEmpty()
        .replace("-", "")
        .equals("UTF8", ignoreCase = true)

    val breaking: String = if (unicode) "✖" else "x"
    val safe: String = if (unicode) "✔" else "+"
}

private interface Style {
    fun bold(text: String): String
    fun dim(text: String): String
    fun red(text: String): String
    fun green(text: String): String
}

/** No escapes at all, for a log file, a test report, or a terminal that said no. */
private object Plain : Style {
    override fun bold(text: String) = text
    override fun dim(text: String) = text
    override fun red(text: String) = text
    override fun green(text: String) = text
}

/**
 * The escapes, and the question of whether to use them.
 *
 * `NO_COLOR` wins outright — it is the one convention every tool agrees on.
 * Otherwise colour is used where something has said it can be read: an
 * attached console, or a `FORCE_COLOR` from a CI that renders escapes in its
 * log viewer. A test JVM under Gradle has neither, and gets plain text, which
 * is the right answer for a message that ends up inside an XML report.
 */
object Ansi : Style {

    val available: Boolean =
        System.getenv("NO_COLOR") == null &&
            (System.console() != null || System.getenv("FORCE_COLOR") != null)

    override fun bold(text: String) = "[1m$text[0m"
    override fun dim(text: String) = "[2m$text[0m"
    override fun red(text: String) = "[31m$text[0m"
    override fun green(text: String) = "[32m$text[0m"
}
