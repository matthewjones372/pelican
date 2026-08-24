package dev.pelican.importer

import java.io.File

/**
 * The signature `pelican-import` publishes, standing in for its body.
 *
 * As with the client generator above it, what it writes is a record of what it
 * was handed — the plugin's half of the job is the arguments, and the
 * generated Kotlin is asserted in that module's own tests and in `:example`.
 *
 * One arity only. The two releases before it are whole library versions of
 * their own, in `dev.pelican.older.importer` and `dev.pelican.oldest.importer`;
 * see the note on the client fake for why they cannot share a class with this
 * one.
 */
@Suppress("LongParameterList")
fun importEndpoints(
    document: File,
    sourceRoot: File,
    packageName: String,
    name: String,
    exclude: Set<String>,
    handlers: String?,
    codec: String?,
    discriminators: Map<String, String>,
): List<File> {
    val directory = packageName.split('.').fold(sourceRoot) { path, part -> File(path, part) }
    directory.mkdirs()
    val hints = discriminators.entries.sortedBy { it.key }.joinToString("+") { "${it.key}=${it.value}" }
    return listOf(
        File(directory, "${name.replaceFirstChar { it.uppercase() }}Endpoints.kt").apply {
            writeText(
                "${document.name}|$packageName|$name|${exclude.sorted().joinToString("+")}" +
                    "|$handlers|$codec|$hints\n",
            )
        },
    )
}
