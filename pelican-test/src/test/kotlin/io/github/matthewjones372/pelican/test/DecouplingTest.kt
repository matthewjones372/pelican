package io.github.matthewjones372.pelican.test

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
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
        withClue("the build must pass -Dpelican.test.runtimeClasspath; see build.gradle.kts") { raw.shouldNotBeNull() }

        val unexpected = raw!!.split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("pelican-test must stay backend-agnostic, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    @Test
    fun `no server library is reachable from the shared test client`() {
        listOf(
            "org.apache.pekko.http.javadsl.server.Directives",
            "org.http4k.core.Request",
            "io.ktor.server.application.Application",
        ).forEach { name ->
            withClue("$name is on pelican-test's classpath; a backend crept back in") {
                shouldThrow<ClassNotFoundException> { Class.forName(name) }
            }
        }
    }

    /**
     * The assertions this module ships throw plain `AssertionError`, so a
     * service that wants a typed test client is not handed somebody else's
     * matcher library along with it.
     *
     * Asked of the *published* classpath rather than of `Class.forName`: this
     * repository's own tests are written with kotest's matchers, so kotest is
     * on the test classpath here by construction and a `Class.forName` check
     * would only be restating that. What a consumer gets is the claim worth
     * holding, and `runtimeClasspath` is where that is written down.
     */
    @Test
    fun `the matchers do not drag in a matcher library`() {
        val raw = System.getProperty("pelican.test.runtimeClasspath")
        raw.shouldNotBeNull()

        val matcherLibraries = listOf("kotest", "hamcrest", "assertj", "truth-", "strikt")
        val found = raw.split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .filter { entry -> matcherLibraries.any { entry.startsWith(it) } }

        withClue("a matcher library is published with pelican-test: $found") {
            found.shouldBeEmpty()
        }
    }
}
