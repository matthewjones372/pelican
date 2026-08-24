package dev.pelican.older.codegen

import dev.pelican.ApiSpec
import java.io.File

/**
 * A `pelican-codegen` from before the codec setting existed.
 *
 * A whole library version in one file, in a package of its own, so that the
 * plugin's fallback has something to fall back *to*. Kept apart from the
 * current fake beside it because the fallback is chosen by which signature
 * exists — put both in one class and the newer one wins every time, which is
 * how a fallback rots without a single test going red.
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
