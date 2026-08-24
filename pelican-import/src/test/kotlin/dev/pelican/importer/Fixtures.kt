package dev.pelican.importer

import java.io.File
import java.nio.file.Files

/**
 * A document written to disk, because that is what the importer reads.
 *
 * It reads a file rather than a string on purpose — references to other files
 * are resolved relative to the one holding them — so the tests hand it real
 * files too, and the multi-file cases need no second code path to exercise.
 */
internal fun documentOf(vararg files: Pair<String, String>): File {
    val directory = Files.createTempDirectory("pelican-import").toFile()
    directory.deleteOnExit()
    return files.map { (name, text) ->
        File(directory, name).also {
            it.parentFile.mkdirs()
            it.writeText(text.trimIndent())
        }
    }.first()
}

/** The endpoints file, which is the one every test here is about. */
internal fun imported(document: File, options: ImportOptions = ImportOptions("test", "test")): String =
    Import.kotlin(document, options).entries.first { it.key.endsWith(ENDPOINTS_FILE_SUFFIX) }.value

internal fun imported(yaml: String, options: ImportOptions = ImportOptions("test", "test")): String =
    imported(documentOf("openapi.yaml" to yaml), options)

/**
 * A minimal document with [paths] in it, so a test about one operation is
 * written as one operation.
 */
internal fun document(paths: String): String =
    listOf(
        "openapi: 3.1.0",
        "info:",
        "  title: Test",
        "  version: \"1.0.0\"",
        "paths:",
        paths.trimIndent().prependIndent("  "),
    ).joinToString("\n")
