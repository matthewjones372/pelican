package io.github.matthewjones372.pelican.previous.importer

import java.io.File

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
