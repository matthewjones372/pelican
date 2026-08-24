package dev.pelican.oldest.importer

import java.io.File

/**
 * A `pelican-import` from before the codec setting existed — the bottom of the
 * importer's fallback, and the version the `older` one beside it falls back to.
 *
 * `older` and `oldest` rather than a version number, because what the plugin
 * cares about is the order and not the release: each one is one signature
 * further back, and the ladder reads the same way whatever the library is
 * called that quarter.
 */
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
