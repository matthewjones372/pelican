package io.github.matthewjones372.pelican.streams

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The claim this module makes about its own classpath, stated as a test.
 *
 * Both halves of the seam are provided. Pekko is, for the reason every other
 * Pekko module here says: `pekko-stream_2.13` and `pekko-stream_3` are different
 * Maven modules holding identical class names, so a resolver sees no conflict
 * and an application on the Scala 3 build that received ours would run both
 * until Pekko's version check stopped it.
 *
 * `lark-stream` is provided for the same reason at one remove: it pins `_2.13`
 * as an `api` dependency of its own, so shipping it would put that suffix back
 * on a consumer's classpath through this module — undoing the decision rather
 * than merely not making it.
 */
class DependenciesTest {

    /**
     * Kotlin's own runtime, the Pelican modules this one is built on, and the
     * `slf4j-api` that `pelican-pekko` publishes on purpose — an API rather than
     * a binding, so the application still picks the implementation.
     */
    private val allowed = listOf("kotlin-stdlib", "annotations-", "jackson-core-", "pelican-", "slf4j-api-")

    @Test
    fun `the published runtime classpath names neither pekko nor lark`() {
        val raw = System.getProperty("pelican.streams.runtimeClasspath")
        withClue("the build must pass -Dpelican.streams.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("a consumer brings their own Pekko and their own lark, so this module ships neither: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
