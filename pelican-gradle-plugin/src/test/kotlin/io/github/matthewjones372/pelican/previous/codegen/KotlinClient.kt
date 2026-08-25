package io.github.matthewjones372.pelican.previous.codegen

import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.codegen.CodecAnnotations
import java.io.File

/**
 * The release that took a codec and knew nothing about a call style. It takes
 * the same `CodecAnnotations` the current one does, because the plugin looks
 * that enum up by name and a copy in this package would not be the class the
 * method signature it is hunting for is written in terms of.
 */
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

fun defaultClientName(title: String): String = title + "Client"
