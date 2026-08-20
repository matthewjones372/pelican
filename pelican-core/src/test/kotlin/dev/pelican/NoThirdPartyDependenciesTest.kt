package dev.pelican

import org.junit.jupiter.api.Assertions.*
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
        assertNotNull(raw, "the build must pass -Dpelican.core.runtimeClasspath; see build.gradle.kts")

        val unexpected = raw!!.split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        assertTrue(
            unexpected.isEmpty(),
            "pelican-core must have no third-party runtime dependencies, but found: $unexpected",
        )
    }

    @Test
    fun `no json library is reachable from core`() {
        listOf(
            "kotlinx.serialization.json.Json",
            "com.fasterxml.jackson.databind.ObjectMapper",
            "org.apache.pekko.http.javadsl.server.Directives",
        ).forEach { name ->
            assertTrue(
                runCatching { Class.forName(name) }.isFailure,
                "$name is on core's classpath; a dependency crept in",
            )
        }
    }

    @Test
    fun `a schema can still be represented without one`() {
        val doc = jsonObj {
            "type" to "object"
            put("properties", jsonObj { "id" to jsonObj { "type" to "integer" } })
            put("required", jsonStrings(listOf("id")))
        }
        assertEquals(
            """{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}""",
            doc.render(),
        )
    }
}
