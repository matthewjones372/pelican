package io.github.matthewjones372.pelican.mcp.server

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test, as every module here states it.
 *
 * The protocol and no SDK. The official Kotlin SDK's server half is written
 * against Ktor — its transports are `Route.mcp` extensions — so adopting it
 * would put a Ktor server, a Ktor client, kotlinx.serialization and a logging
 * facade behind `mcpServe` on a service that runs something else. What is
 * spoken here is JSON-RPC 2.0 over lines of text, and core already has the
 * JSON tree to speak it with.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib", "annotations-", "pelican-core", "pelican-schema", "pelican-mcp",
    )

    @Test
    fun `the main runtime classpath is the tool values and core, and nothing else`() {
        val raw = System.getProperty("pelican.mcp.server.runtimeClasspath")
        withClue("the build must pass -Dpelican.mcp.server.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("pelican-mcp-server must stay core plus pelican-mcp, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
