package io.github.matthewjones372.pelican.mcp

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test, as every module here states it.
 *
 * Core, the schema pass, and no MCP SDK: a tool list is a value, and deriving
 * one is separate from serving it. A module carrying an SDK and a transport as
 * well would put a server in front of everybody who wanted only the schemas.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "pelican-core", "pelican-schema")

    @Test
    fun `the main runtime classpath is core and the schema pass, and nothing else`() {
        val raw = System.getProperty("pelican.mcp.runtimeClasspath")
        withClue("the build must pass -Dpelican.mcp.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("pelican-mcp must stay core plus the schema pass, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
