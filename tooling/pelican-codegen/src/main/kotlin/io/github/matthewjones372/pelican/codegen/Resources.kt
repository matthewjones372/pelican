package io.github.matthewjones372.pelican.codegen

/**
 * The generator templates: real Kotlin, kept beside the code that emits them
 * rather than as string constants inside it.
 */

/** [name], resolved against [owner]'s package, as text. */
fun template(owner: Class<*>, name: String): String =
    template(owner.classLoader, "${owner.packageName.replace('.', '/')}/$name")

/**
 * The same, by absolute path.
 *
 * Read through a connection of its own rather than `getResourceAsStream`,
 * whose stream belongs to the loader: `URLClassLoader.close()` closes every one
 * it handed out, and Gradle closes a generation worker's loader as soon as any
 * work item sharing it finishes. A parallel build failed partway through a
 * template with `java.io.IOException: Stream closed`. Caching off, so the file
 * handle is ours to close and nobody else's.
 */
fun template(loader: ClassLoader, path: String): String {
    val url = loader.getResource(path) ?: error("$path is missing from the generator's resources")
    return url.openConnection().apply { useCaches = false }.getInputStream()
        .use { it.readBytes() }
        .decodeToString()
}
