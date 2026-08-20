package dev.pelican

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * `pelican-core` is meant to be describable-in-a-vacuum: endpoint values, a
 * minimal JSON tree, and nothing else. This is the same idea as
 * `DecouplingTest`'s Pekko-absence check, one level stricter — rather than
 * naming the libraries that must be absent, it asserts that *nothing* beyond
 * the Kotlin standard library is there at all.
 */
class NoThirdPartyDependenciesTest {

    /** Artifacts the Kotlin plugin puts on every module's classpath. */
    private val allowed = listOf("kotlin-stdlib", "annotations-")

    @Test
    fun `the main runtime classpath is the kotlin standard library and nothing else`() {
        val raw = System.getProperty("pelican.core.runtimeClasspath")
        withClue("the build must pass -Dpelican.core.runtimeClasspath; see build.gradle.kts") { raw.shouldNotBeNull() }

        val unexpected = raw!!.split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("pelican-core must have no third-party runtime dependencies, but found: $unexpected") {
            unexpected.isEmpty() shouldBe true
        }
    }

    @Test
    fun `no json library is reachable from core`() {
        listOf(
            "kotlinx.serialization.json.Json",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "org.apache.pekko.http.javadsl.server.Directives",
        ).forEach { name ->
            withClue("$name is on core's classpath; a dependency crept in") {
                runCatching { Class.forName(name) }.isFailure shouldBe true
            }
        }
    }

    @Test
    fun `a schema can still be represented without one`() {
        val doc = jsonObj {
            "type" to "object"
            put("properties", jsonObj { "id" to jsonObj { "type" to "integer" } })
            put("required", jsonStrings(listOf("id")))
        }
        doc.render() shouldBe """{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}"""
    }
}
