package io.github.matthewjones372.pelican.codegen

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class ResourcesTest {

    /**
     * A loader whose streams are dead and whose URLs are not. `URLClassLoader.
     * close()` closes every stream it handed out, and Gradle closes a
     * generation worker's loader as soon as any work item sharing it finishes:
     * a template read through `getResourceAsStream` failed a parallel build
     * with `java.io.IOException: Stream closed` partway through the file.
     */
    private class Closed(loader: ClassLoader) : ClassLoader(loader) {
        override fun getResourceAsStream(name: String): InputStream = object : InputStream() {
            override fun read(): Int = throw IOException("Stream closed")
        }
    }

    @Test
    fun `a template comes back whole`() {
        loader().use { template(it, PATH) shouldBe TEXT }
    }

    @Test
    fun `a template is read through the resource url, not a stream the loader can close`() {
        loader().use { template(Closed(it), PATH) shouldBe TEXT }
    }

    @Test
    fun `a template that is not there names the path it looked under`() {
        val failure = shouldThrow<IllegalStateException> {
            loader().use { template(it, "templates/absent.kt") }
        }
        failure.message shouldContain "templates/absent.kt"
    }

    /** The templates ship inside a jar, so the test reads one. */
    private fun loader(): URLClassLoader {
        val jar = Files.createTempFile("pelican-templates", ".jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(ZipEntry(PATH))
            out.write(TEXT.toByteArray())
            out.closeEntry()
        }
        jar.toFile().deleteOnExit()
        return URLClassLoader(arrayOf(jar.toUri().toURL()), null)
    }
}

private const val PATH = "templates/probe.kt"

private const val TEXT = "fun probe(): String = \"generated\"\n"
