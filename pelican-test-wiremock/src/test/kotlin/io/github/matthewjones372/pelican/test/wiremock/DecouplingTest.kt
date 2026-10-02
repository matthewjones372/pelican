package io.github.matthewjones372.pelican.test.wiremock

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/** What this module puts on a consumer's classpath: core, `pelican-test`, WireMock, and no server library. */
class DecouplingTest {

    private val allowed = listOf(
        "pelican-core",
        "wiremock-standalone-",
        "kotlin-stdlib",
        "kotlin-reflect",
        "annotations-",
        "jackson-core-",
    )

    /**
     * `pelican-test`, which `stubFile` builds its requests with. Anchored to the version rather than
     * allowed as a prefix, because `pelican-test-pekko` starts the same way and would bring a backend.
     */
    private val allowedTestModule = Regex("""pelican-test-\d.*""")

    @Test
    fun `the main runtime classpath is core, pelican-test and WireMock, with no backend and no JUnit`() {
        val raw = System.getProperty("pelican.test.runtimeClasspath")
        withClue("the build must pass -Dpelican.test.runtimeClasspath; see build.gradle.kts") { raw.shouldNotBeNull() }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } || allowedTestModule.matches(entry) }

        withClue("pelican-test-wiremock must not bring a server library or a test framework, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
