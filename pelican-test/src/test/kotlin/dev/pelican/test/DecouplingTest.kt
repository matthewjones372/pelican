package dev.pelican.test

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The claim this module makes about its own classpath, stated as a test.
 *
 * `pelican-test` used to declare `api(project(":pelican-pekko"))` for the sake
 * of one in-memory transport and one convenience function, which meant a Ktor
 * or http4k service that wanted a typed client got Pekko HTTP and Pekko streams
 * on its test classpath as well. The backend-specific pieces moved to
 * `pelican-test-pekko` and `pelican-test-http4k`; this is what stops them
 * moving back.
 *
 * Every other module in the repository has a guard like this one —
 * `NoThirdPartyDependenciesTest` on core, `DecouplingTest` on each backend.
 * This module was the hole in that story.
 */
class DecouplingTest {

    /** Artifacts the Kotlin plugin puts on every module's classpath. */
    private val allowed = listOf("kotlin-stdlib", "kotlin-reflect", "annotations-", "pelican-core")

    @Test
    fun `the main runtime classpath is core, the kotlin runtime, and nothing else`() {
        val raw = System.getProperty("pelican.test.runtimeClasspath")
        assertNotNull(raw, "the build must pass -Dpelican.test.runtimeClasspath; see build.gradle.kts")

        val unexpected = raw!!.split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        assertTrue(
            unexpected.isEmpty(),
            "pelican-test must stay backend-agnostic, but found: $unexpected",
        )
    }

    @Test
    fun `no server library is reachable from the shared test client`() {
        listOf(
            "org.apache.pekko.http.javadsl.server.Directives",
            "org.http4k.core.Request",
            "io.ktor.server.application.Application",
        ).forEach { name ->
            assertTrue(
                runCatching { Class.forName(name) }.isFailure,
                "$name is on pelican-test's classpath; a backend crept back in",
            )
        }
    }

    @Test
    fun `the matchers do not drag in a matcher library`() {
        assertTrue(
            runCatching { Class.forName("io.kotest.matchers.Matcher") }.isFailure,
            "kotest is on pelican-test's classpath; the assertions here throw AssertionError instead",
        )
    }
}
