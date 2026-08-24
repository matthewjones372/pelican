package dev.pelican.importer

import java.io.File

/**
 * The signature `pelican-import` publishes, standing in for its body.
 *
 * As with the client generator above it, what it writes is a record of what it
 * was handed — the plugin's half of the job is the arguments, and the
 * generated Kotlin is asserted in that module's own tests and in `:example`.
 *
 * The arity a previous release published is in `dev.pelican.older.importer`
 * rather than here; see the note on the client fake for why the two cannot
 * share a class.
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
): List<File> {
    val directory = packageName.split('.').fold(sourceRoot) { path, part -> File(path, part) }
    directory.mkdirs()
    return listOf(
        File(directory, "${name.replaceFirstChar { it.uppercase() }}Endpoints.kt").apply {
            writeText(
                "${document.name}|$packageName|$name|${exclude.sorted().joinToString("+")}|$handlers|$codec\n",
            )
        },
    )
}
