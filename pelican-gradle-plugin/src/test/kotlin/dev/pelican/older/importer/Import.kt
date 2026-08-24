package dev.pelican.older.importer

import java.io.File

/** A `pelican-import` from before the codec setting existed; see the older client fake. */
fun importEndpoints(
    document: File,
    sourceRoot: File,
    packageName: String,
    name: String,
    exclude: Set<String>,
    handlers: String?,
): List<File> {
    val directory = packageName.split('.').fold(sourceRoot) { path, part -> File(path, part) }
    directory.mkdirs()
    return listOf(
        File(directory, "${name.replaceFirstChar { it.uppercase() }}Endpoints.kt").apply {
            writeText("${document.name}|$packageName|$name|${exclude.sorted().joinToString("+")}|$handlers\n")
        },
    )
}
