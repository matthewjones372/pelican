package io.github.matthewjones372.pelican.importer

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
    allowRemote: Set<String>,
    lockfile: File?,
): List<File> {
    val directory = packageName.split('.').fold(sourceRoot) { path, part -> File(path, part) }
    directory.mkdirs()
    val hints = discriminators.entries.sortedBy { it.key }.joinToString("+") { "${it.key}=${it.value}" }
    return listOf(
        File(directory, "${name.replaceFirstChar { it.uppercase() }}Endpoints.kt").apply {
            writeText(
                "${document.name}|$packageName|$name|${exclude.sorted().joinToString("+")}" +
                    "|$handlers|$codec|$hints|${allowRemote.sorted().joinToString("+")}|${lockfile?.name}\n",
            )
        },
    )
}

fun updateRemoteLock(
    document: File,
    lockfile: File,
    name: String,
    allowRemote: Set<String>,
    acceptChanges: Boolean,
): List<String> {
    lockfile.parentFile?.mkdirs()
    lockfile.writeText("${document.name}|$name|${allowRemote.sorted().joinToString("+")}|$acceptChanges\n")
    return listOf("Wrote $lockfile")
}
