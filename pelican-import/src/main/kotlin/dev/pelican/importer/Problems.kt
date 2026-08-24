package dev.pelican.importer

/**
 * What the document says that Pelican cannot describe, collected rather than
 * thrown one at a time.
 *
 * A strict import that stopped at the first problem would be a queue: fix,
 * rerun, wait for the build, find the next one. The decision a reader has to
 * make — which operations to exclude, or what to change in a document they own
 * — is one decision about the whole list, so the whole list is what the failure
 * carries.
 *
 * One problem per operation, not all of them. Once an operation has something
 * in it that cannot be described, the rest of what it says is being read
 * through that, and a second complaint about the same operation is usually the
 * first one again in another position.
 */
internal class Problems {

    private val found = LinkedHashMap<String, Problem>()

    fun record(operation: String, id: String, path: JsonPath, message: String) {
        found.putIfAbsent(operation, Problem(operation, id, path, message))
    }

    fun failIfAny(name: String) {
        if (found.isEmpty()) return
        throw ImportFailure(
            buildString {
                append(found.size)
                append(if (found.size == 1) " operation cannot" else " operations cannot")
                appendLine(" be described as it stands:")
                appendLine()
                found.values.forEach { problem ->
                    appendLine("  ${problem.operation}")
                    appendLine("    at ${problem.path}")
                    problem.message.lines().forEach { appendLine("    $it") }
                    appendLine()
                }
                appendLine("Change the document, or leave these out of the import on purpose:")
                appendLine()
                appendLine("    pelican {")
                appendLine("        endpoints {")
                appendLine("            create(\"$name\") {")
                appendLine("                exclude(${found.values.joinToString(", ") { "\"${it.id}\"" }})")
                appendLine("            }")
                appendLine("        }")
                append("    }")
            },
        )
    }
}

private class Problem(val operation: String, val id: String, val path: JsonPath, val message: String)

/**
 * Thrown by the reader when it meets something it cannot describe, and caught
 * one operation later. Nothing outside this module sees it: what a caller gets
 * is the [ImportFailure] holding every one of them.
 */
internal class Unsupported(val path: JsonPath, override val message: String) : RuntimeException(message)

internal fun unsupported(path: JsonPath, message: String): Nothing = throw Unsupported(path, message)
