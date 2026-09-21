package example.style

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Test names are sentences in backticks, and the Kotlin compiler writes the
 * class file for one under that sentence. A JVM whose `sun.jnu.encoding` is
 * `ANSI_X3.4-1968` — an unset `LANG`, which is the default in many container
 * images — cannot write a name with an em dash in it, and reports an internal
 * compiler error rather than the name it could not write. See spec 0047.
 *
 * Which sources it judges is not decided here: the build hands them over and
 * declares the same directories as inputs of the task that runs this test, for
 * the reason `FunctionalStyleTest` gives. See `example/build.gradle.kts`.
 */
class AsciiDeclarationNamesTest {

    /**
     * Declaration keywords rather than every backtick pair, so prose in a KDoc
     * that quotes an identifier is not a finding.
     */
    private val backtickedDeclaration = Regex("""\b(?:fun|val|var|class|object|interface)\s+`([^`\n]*)`""")

    private fun handedOver(key: String): String =
        System.getProperty(key).also {
            withClue("the build must pass -D$key; see example/build.gradle.kts") { it.shouldNotBeNull() }
        }!!

    private fun repoRoot(): File = File(handedOver("pelican.ascii.repoRoot"))

    private fun sourceRoots(): List<File> = handedOver("pelican.ascii.sources")
        .split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .map(::File)

    private fun sources(): List<File> = sourceRoots()
        .flatMap { it.walkTopDown().filter { file -> file.isFile && file.extension == "kt" }.toList() }

    /** The wiring, asserted rather than assumed — `FunctionalStyleTest` says why. */
    @Test
    fun `the build hands this test the sources it judges`() {
        val roots = sourceRoots()
        withClue("the build named no source roots at all") { roots.shouldNotBeEmpty() }

        val missing = roots.filterNot { it.isDirectory }
        withClue("the build named source roots that are not there: $missing") { missing.shouldBeEmpty() }

        withClue("only ${sources().size} files were handed over; the gate is judging almost nothing") {
            sources().size shouldBeGreaterThan 100
        }
    }

    @Test
    fun `no declaration is named with a character a file name cannot hold`() {
        val root = repoRoot()
        val offenders = sources().flatMap { file ->
            backtickedDeclaration.findAll(file.readText())
                .map { it.groupValues[1] }
                .filter { name -> name.any { it.code > MAX_ASCII } }
                .map { "${file.relativeTo(root).path}: `$it`" }
                .toList()
        }.sorted()

        withClue(
            "a backticked name becomes a class file name, and on an ASCII locale the compiler cannot " +
                "write it — the build dies naming an internal compiler error instead: $offenders",
        ) {
            offenders.shouldBeEmpty()
        }
    }

    private companion object {
        /** The last code point `ANSI_X3.4-1968` can encode. */
        const val MAX_ASCII = 127
    }
}
