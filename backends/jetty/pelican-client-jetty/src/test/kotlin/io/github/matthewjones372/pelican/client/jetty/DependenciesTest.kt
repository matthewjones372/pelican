package io.github.matthewjones372.pelican.client.jetty

import io.github.matthewjones372.pelican.ClientTransport
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.File

/** The claim this module makes about its own classpath: core plus Jetty's client half, and no server. */
class DependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "jackson-core-",
        "pelican-core",
        "jetty-client-",
        "jetty-alpn-client-",
        "jetty-http-",
        "jetty-io-",
        "jetty-util-",
        "jetty-compression-",
        "slf4j-api-",
    )

    @Test
    fun `the main runtime classpath is core, the kotlin runtime and jetty's client, and nothing else`() {
        val raw = System.getProperty("pelican.client.jetty.runtimeClasspath")
        withClue("the build must pass -Dpelican.client.jetty.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("this adapter must carry no library beyond Jetty's client, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }

    @Test
    fun `no other http library, and no server, is reachable from the adapter`() {
        listOf(
            "io.ktor.client.HttpClient",
            "org.apache.pekko.http.javadsl.Http",
            "org.http4k.core.Request",
            "okhttp3.OkHttpClient",
            "org.eclipse.jetty.server.Server",
        ).forEach { name ->
            withClue("$name is on this module's classpath; a second HTTP stack crept in") {
                shouldThrow<ClassNotFoundException> { Class.forName(name) }
            }
        }
    }

    @Test
    fun `core finds this adapter as the default transport`() {
        ClientTransport.default().shouldBeInstanceOf<JettyHttpTransport>()
    }
}
