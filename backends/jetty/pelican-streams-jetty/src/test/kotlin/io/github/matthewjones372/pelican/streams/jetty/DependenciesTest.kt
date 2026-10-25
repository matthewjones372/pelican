package io.github.matthewjones372.pelican.streams.jetty

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** The Jetty twin ships Jetty and Pelican, and neither Pekko nor surefold: the consumer names its surefold backend. */
class DependenciesTest {

    private val allowed =
        listOf("kotlin-stdlib", "annotations-", "jackson-core-", "pelican-", "slf4j-api-", "jetty-")

    @Test
    fun `the published runtime classpath names neither pekko nor surefold`() {
        val raw = System.getProperty("pelican.streams.runtimeClasspath")
        withClue("the build must pass -Dpelican.streams.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("this module ships no Pekko and no surefold: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
