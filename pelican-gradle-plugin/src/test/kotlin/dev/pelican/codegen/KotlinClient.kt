package dev.pelican.codegen

import dev.pelican.ApiSpec
import java.io.File

/**
 * The signature `pelican-codegen` publishes, standing in for its body. The
 * file it writes records what it was handed, so a test can assert on the
 * defaults the plugin computes rather than on generated Kotlin.
 *
 * An extension function on `ApiSpec` compiles to a static method taking the
 * receiver first, which is what the real one is and what this one has to be.
 */
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
