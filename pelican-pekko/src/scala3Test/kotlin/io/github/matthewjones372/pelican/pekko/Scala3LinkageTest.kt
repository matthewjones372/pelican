package io.github.matthewjones372.pelican.pekko

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile

/**
 * Every Pekko type any Pelican module's bytecode names, loaded against the
 * Scala 3 cross-build.
 *
 * The end-to-end test beside this one reaches perhaps twenty of them. Reading
 * the constant pools asks the question of the artifact instead of the author,
 * so it covers what a request happens not to touch and cannot rot when
 * somebody imports a new Pekko class.
 *
 * Internal names carry `/` separators, so a string constant naming a package
 * in dotted form is not mistaken for a class reference.
 */
class Scala3LinkageTest {

    /**
     * Fewer than this and something is wrong with the scan rather than with
     * the classpath: a green test over an empty set proves nothing. Sixty-one
     * distinct types when this was written — the five modules name 148 between
     * them, but they share the `javadsl` model, so the union is far smaller
     * than the sum.
     */
    private val atLeast = 50

    private val pekkoName = Regex("org/apache/pekko/[A-Za-z0-9/\\\$_]+")

    private fun classpath(): List<File> =
        System.getProperty("java.class.path").split(File.pathSeparator).map(::File)

    private fun pelicanEntries(): List<File> =
        classpath().filter { "pelican-" in it.path && it.exists() }

    private fun loads(name: String): Boolean = try {
        Class.forName(name, false, javaClass.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun namesIn(bytes: ByteArray): Set<String> =
        pekkoName.findAll(String(bytes, Charsets.ISO_8859_1))
            .map { it.value.replace('/', '.') }
            .toSet()

    private fun namesIn(entry: File): Set<String> = when {
        entry.isDirectory ->
            entry.walkTopDown().filter { it.extension == "class" }
                .flatMap { namesIn(it.readBytes()) }.toSet()

        entry.extension == "jar" ->
            JarFile(entry).use { jar ->
                jar.entries().toList()
                    .filter { it.name.endsWith(".class") }
                    .flatMap { namesIn(jar.getInputStream(it).readBytes()) }
                    .toSet()
            }

        else -> emptySet()
    }

    @Test
    fun `every Pekko type Pelican names resolves on the Scala 3 cross-build`() {
        val scanned = pelicanEntries()
        withClue("no Pelican classes were on the classpath to scan") {
            scanned.size shouldBeGreaterThan 0
        }

        val referenced = scanned.flatMap { namesIn(it) }.toSet()
        println("Pekko types referenced by ${scanned.size} Pelican entries: ${referenced.size}")

        withClue("the scan found $referenced, which is too few to be the whole surface") {
            referenced.size shouldBeGreaterThan atLeast
        }

        val missing = referenced.filterNot { name -> loads(name) }

        withClue("these types are named by Pelican's bytecode and absent from Pekko's _3 build") {
            missing.shouldBeEmpty()
        }
    }

    @Test
    fun `no Scala 2_13 artifact reaches this classpath`() {
        val wrongCrossBuild = classpath().filter { "_2.13" in it.name }

        withClue("Pekko is compileOnly in every Pelican module, so none of it should arrive here") {
            wrongCrossBuild.shouldBeEmpty()
        }
    }
}
