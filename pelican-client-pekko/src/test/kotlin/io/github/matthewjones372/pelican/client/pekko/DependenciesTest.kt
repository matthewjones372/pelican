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
 * An adapter is what a caller adds to choose an HTTP stack, so it must carry
 * exactly one: core, Pekko, and Pekko's own closure. `pelican-pekko` is not on
 * the list either — sending a request and interpreting a description into a
 * route are separate decisions, and a caller making only calls should not
 * compile the interpreter in.
 */
class DependenciesTest {

    /** Kotlin's own runtime, core, and what Pekko HTTP brings with it. */
    private val allowed = listOf(
        "kotlin-stdlib", "annotations-", "pelican-core",
        "pekko-", "scala-library", "config-", "ssl-config-core",
        "reactive-streams", "parboiled", "slf4j-api",
    )

    @Test
    fun `the main runtime classpath is core, kotlin, pekko, and nothing else`() {
        val raw = System.getProperty("pelican.client.pekko.runtimeClasspath")
        withClue("the build must pass -Dpelican.client.pekko.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("this adapter must carry nothing but core and Pekko, but found: $unexpected") {
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
     * between two, which is why this module stays off `pelican-client-java`'s
     * test classpath and that one stays off this.
     */
    @Test
    fun `core finds this adapter as the default transport`() {
        ClientTransport.default().shouldBeInstanceOf<PekkoHttpTransport>()
    }
}
