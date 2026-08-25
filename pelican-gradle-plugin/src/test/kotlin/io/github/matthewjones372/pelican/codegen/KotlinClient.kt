package io.github.matthewjones372.pelican.codegen

import io.github.matthewjones372.pelican.ApiSpec
import java.io.File

fun writeKotlinClient(
    spec: ApiSpec,
    sourceRoot: File,
    packageName: String,
    clientName: String,
    baseUrl: String?,
    includeHidden: Boolean,
    codec: CodecAnnotations,
): File {
    val directory = packageName.split('.').fold(sourceRoot) { path, part -> File(path, part) }
    directory.mkdirs()
    return File(directory, "$clientName.kt").apply {
        writeText("${spec.title}|$packageName|$clientName|$baseUrl|$includeHidden|$codec\n")
    }
}

/** The enum the real one takes, named the same way, because the lookup is by type. */
enum class CodecAnnotations { JACKSON, KOTLINX }

fun defaultClientName(title: String): String = title + "Client"
