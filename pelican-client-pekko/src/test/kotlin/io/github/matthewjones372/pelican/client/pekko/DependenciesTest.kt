package io.github.matthewjones372.pelican.client.pekko

import io.github.matthewjones372.pelican.ClientTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The claim this module makes about its own classpath, stated as a test.
 *
 * Pekko is provided, not shipped: `pekko-actor_2.13` and `pekko-actor_3` are
 * different Maven modules holding identical class names, so a resolver sees no
 * conflict and an application on the Scala 3 build that received ours would run
 * both until Pekko's version check stopped it. Shipping none lets the
 * application name the suffix it already runs.
 */
class DependenciesTest {

    /** Kotlin's own runtime and core. Nothing here has a Scala suffix. */
    private val allowed = listOf("kotlin-stdlib", "annotations-", "pelican-core")

    @Test
    fun `the published runtime classpath is core and kotlin, with no pekko on it`() {
        val raw = System.getProperty("pelican.client.pekko.runtimeClasspath")
        withClue("the build must pass -Dpelican.client.pekko.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("a consumer brings their own Pekko, so this module must ship none, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    @Test
    fun `no second http stack, and no interpreter, is reachable from the adapter`() {
        listOf(
            "io.ktor.client.HttpClient",
            "org.http4k.core.Request",
            "okhttp3.OkHttpClient",
            "io.github.matthewjones372.pelican.pekko.PelicanServer",
            "io.github.matthewjones372.pelican.openapi.OpenApiKt",
        ).forEach { name ->
            withClue("$name is on this module's classpath") {
                shouldThrow<ClassNotFoundException> { Class.forName(name) }
            }
        }
    }

    /**
     * What makes adding the module the whole of choosing it — as long as it is
     * the only adapter present. `ClientTransport.default()` refuses to pick
     * between two, so a second adapter never joins this test classpath.
     */
    @Test
    fun `core finds this adapter as the default transport`() {
        ClientTransport.default().shouldBeInstanceOf<PekkoHttpTransport>()
    }
}
