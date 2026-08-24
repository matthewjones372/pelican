package io.github.matthewjones372.pelican.previous.importer

import java.io.File

/**
 * A `pelican-import` from before remote references existed, which still takes
 * the discriminator hints.
 *
 * `previous`, then `older`, then `oldest`: one signature further back at each
 * step, named for the order rather than the release, because the order is what
 * decides which fallback a lookup takes. This one is the rung the remote
 * allowlist has to refuse to step past — an importer without a lockfile in its
 * signature has no hash check either, so falling back here with hosts allowed
 * would fetch and generate from whatever came back.
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
