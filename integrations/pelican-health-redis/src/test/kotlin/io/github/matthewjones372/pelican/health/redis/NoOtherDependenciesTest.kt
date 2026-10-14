package io.github.matthewjones372.pelican.health.redis

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test

/** The Redis client is the service's, `compileOnly` here, so what ships is health and core. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "jackson-core-", "pelican-core", "pelican-health")

    @Test
    fun `the main runtime classpath is health and core, and no Redis client`() {
        val raw = System.getProperty("pelican.health.redis.runtimeClasspath")
        withClue("the build must pass -Dpelican.health.redis.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("pelican-health-redis must ship no client of its own, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
