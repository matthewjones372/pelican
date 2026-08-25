package io.github.matthewjones372.pelican.older.codegen

import io.github.matthewjones372.pelican.ApiSpec
import java.io.File

fun writeKotlinClient(
    spec: ApiSpec,
    sourceRoot: File,
    packageName: String,
    clientName: String,
    baseUrl: String?,
    includeHidden: Boolean,
): File {
    val directory = packageName.split('.').fold(sourceRoot) { path, part -> File(path, part) }
    directory.mkdirs()
    return File(directory, "$clientName.kt").apply {
        writeText("${spec.title}|$packageName|$clientName|$baseUrl|$includeHidden\n")
    }
}

fun defaultClientName(title: String): String = title + "Client"
