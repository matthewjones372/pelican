package io.github.matthewjones372.pelican.codegen

import io.github.matthewjones372.pelican.ApiSpec
import java.io.File

// The parameter list is the point: this stands in for a signature the plugin
// hunts for by name and by type, so it has to be exactly the one the library
// publishes — the receiver of the real extension function included.
@Suppress("LongParameterList")
fun writeKotlinClient(
    spec: ApiSpec,
    sourceRoot: File,
    packageName: String,
    clientName: String,
    baseUrl: String?,
    includeHidden: Boolean,
    codec: CodecAnnotations,
    callStyle: CallStyle,
): File {
    val directory = packageName.split('.').fold(sourceRoot) { path, part -> File(path, part) }
    directory.mkdirs()
    return File(directory, "$clientName.kt").apply {
        writeText("${spec.title}|$packageName|$clientName|$baseUrl|$includeHidden|$codec|$callStyle\n")
    }
}

/** The enums the real one takes, named the same way, because the lookup is by type. */
enum class CodecAnnotations { JACKSON, KOTLINX }

enum class CallStyle { BLOCKING, SUSPENDING }

fun defaultClientName(title: String): String = title + "Client"
