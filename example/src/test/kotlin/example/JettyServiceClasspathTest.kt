package example

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.io.File

/** Spec 0075: a service built from the Jetty modules runs with no Pekko and no Scala. */
class JettyServiceClasspathTest {

    private val forbidden = listOf("org.apache.pekko", "pekko-", "scala-library", "scala3-library")

    /** Each entry is `<jar file name> <component>`, as `example/build.gradle.kts` writes it. */
    private fun classpath(property: String): List<String> {
        val raw = System.getProperty(property)
        withClue("the build must pass -D$property; see example/build.gradle.kts") { raw.shouldNotBeNull() }
        return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
    }

    private fun refused(entries: List<String>): List<String> =
        entries.filter { entry -> forbidden.any { entry.contains(it) } }

    @Test
    fun `the jetty modules bring no pekko and no scala to a service's runtime`() {
        val entries = classpath("pelican.jetty.service.runtimeClasspath")
        withClue("a Jetty service must run without Pekko, but its runtime classpath has: ${refused(entries)}") {
            refused(entries).shouldBeEmpty()
        }
    }

    @Test
    fun `adding pelican-pekko to the same set is refused`() {
        val entries = classpath("pelican.jetty.service.withPekko.runtimeClasspath")
        val jars = refused(entries).map { it.substringBefore(' ') }
        jars.shouldHaveSize(1)
        jars.single() shouldStartWith "pelican-pekko-"
    }
}
