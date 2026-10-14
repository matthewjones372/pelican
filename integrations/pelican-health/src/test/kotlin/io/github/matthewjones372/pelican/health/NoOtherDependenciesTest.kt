package io.github.matthewjones372.pelican.health

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test

/** Health is core plus the JDK; a check over a third-party client is a leaf module (spec 0069). */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "jackson-core-", "pelican-core")

    @Test
    fun `the main runtime classpath is core and nothing else`() {
        val raw = System.getProperty("pelican.health.runtimeClasspath")
        withClue("the build must pass -Dpelican.health.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("pelican-health must stay core and nothing else, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    @Test
    fun `no server library is reachable from the probes`() {
        listOf(
            "org.apache.pekko.http.javadsl.server.Directives",
            "io.github.matthewjones372.pelican.openapi.OpenApiKt",
        ).forEach { name ->
            withClue("$name is on pelican-health's classpath; a dependency crept in") {
                shouldThrow<ClassNotFoundException> { Class.forName(name) }
            }
        }
    }
}
