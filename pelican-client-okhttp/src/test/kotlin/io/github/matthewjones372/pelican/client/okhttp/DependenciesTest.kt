package io.github.matthewjones372.pelican.client.okhttp

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
 * exactly one: core, OkHttp, and OkHttp's own closure, which is okio and the
 * Kotlin runtime. That closure is the whole reason this module exists — an
 * Android app already ships every jar on this list.
 */
class DependenciesTest {

    /** Kotlin's own runtime, core, and what OkHttp brings with it. */
    private val allowed = listOf("kotlin-stdlib", "annotations-", "pelican-core", "okhttp", "okio")

    @Test
    fun `the main runtime classpath is core, kotlin, okhttp, and nothing else`() {
        val raw = System.getProperty("pelican.client.okhttp.runtimeClasspath")
        withClue("the build must pass -Dpelican.client.okhttp.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("this adapter must carry nothing but core and OkHttp, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    /**
     * No Android anything, asked as a test rather than promised in a comment.
     * The module runs on Android because it depends on nothing that does not,
     * and an AndroidX artifact arriving here would make that a coincidence.
     */
    @Test
    fun `no second http stack, no interpreter, and nothing from Android is reachable`() {
        listOf(
            "io.ktor.client.HttpClient",
            "org.apache.pekko.http.javadsl.Http",
            "org.http4k.core.Request",
            "org.apache.http.client.HttpClient",
            "retrofit2.Retrofit",
            "androidx.annotation.NonNull",
            "io.github.matthewjones372.pelican.ktor.PelicanServer",
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
     * between two, which is why this module stays off the other three adapters'
     * test classpaths and they stay off this.
     */
    @Test
    fun `core finds this adapter as the default transport`() {
        ClientTransport.default().shouldBeInstanceOf<OkHttpTransport>()
    }
}
