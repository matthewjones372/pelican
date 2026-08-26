package io.github.matthewjones372.pelican.arrow

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test — the claim `docs/modules.md` makes for it: core, Arrow, and nothing
 * else. No JSON library, no HTTP library, no second functional stack.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "pelican-core", "arrow-")

    @Test
    fun `the main runtime classpath is core plus arrow, and nothing else`() {
        val raw = System.getProperty("pelican.arrow.runtimeClasspath")
        withClue("the build must pass -Dpelican.arrow.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("pelican-arrow must stay core plus arrow, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
