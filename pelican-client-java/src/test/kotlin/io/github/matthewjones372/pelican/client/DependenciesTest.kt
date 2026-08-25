package io.github.matthewjones372.pelican.client

import io.github.matthewjones372.pelican.ClientTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The claim this module makes about its own classpath, stated as a test.
 *
 * An adapter is what a caller adds to choose an HTTP stack, so it must not
 * bring a second one along. This one is core plus `java.net.http`, which ships
 * with the JDK and appears on no classpath at all.
 */
class DependenciesTest {

    /** Artifacts the Kotlin plugin puts on every module's classpath. */
    private val allowed = listOf("kotlin-stdlib", "annotations-", "pelican-core")

    @Test
    fun `the main runtime classpath is core, the kotlin runtime, and nothing else`() {
        val raw = System.getProperty("pelican.client.java.runtimeClasspath")
        withClue("the build must pass -Dpelican.client.java.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("this adapter must carry no HTTP library of its own, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    @Test
    fun `no other http library is reachable from the adapter`() {
        listOf(
            "io.ktor.client.HttpClient",
            "org.apache.pekko.http.javadsl.Http",
            "org.http4k.core.Request",
            "okhttp3.OkHttpClient",
        ).forEach { name ->
            withClue("$name is on this module's classpath; a second HTTP stack crept in") {
                shouldThrow<ClassNotFoundException> { Class.forName(name) }
            }
        }
    }

    /**
     * What makes adding the module the whole of choosing it: core finds the
     * adapter through `META-INF/services` rather than by naming it, which it
     * could not do without depending on it.
     */
    @Test
    fun `core finds this adapter as the default transport`() {
        ClientTransport.default().shouldBeInstanceOf<JavaHttpTransport>()
    }
}
