package io.github.matthewjones372.pelican.schema

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test — the claim `docs/modules.md` makes for it.
 *
 * Core and nothing else, which is the whole reason it is not a file inside
 * `pelican-openapi`: wanting one type described should not mean acquiring a
 * document generator. The codecs are test-scoped, so the assertion is about
 * what ships rather than about what this JVM can load.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "pelican-core")

    @Test
    fun `the main runtime classpath is core, and nothing else`() {
        val raw = System.getProperty("pelican.schema.runtimeClasspath")
        withClue("the build must pass -Dpelican.schema.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("pelican-schema must stay core-only, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
