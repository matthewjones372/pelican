package example.backends

import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.kotlinFunction
import io.github.matthewjones372.pelican.http4k.start as startOnHttp4k
import io.github.matthewjones372.pelican.ktor.start as startOnKtor
import io.github.matthewjones372.pelican.pekko.start as startOnPekko

/**
 * The call a user types first, pinned to one shape on all three backends.
 *
 * `AllBackendsTest` asks the three servers the same questions once they are
 * running; nothing there notices that starting them used to be spelled three
 * ways — `start(host, port)` on Pekko, `start(port, host)` on Ktor, no host at
 * all on http4k — or that a bare `start()` reached the network on one of them
 * and loopback on the other.
 */
class StartParityTest {

    /**
     * The entry point each backend publishes, ignoring Pekko's overload taking
     * an `ActorSystem`: `port` first is what identifies the one under test.
     */
    private fun entryPoint(facade: String, name: String): KFunction<*> =
        Class.forName(facade).methods
            .filter { it.name == name }
            .mapNotNull { it.kotlinFunction }
            .single { it.parameters.getOrNull(1)?.name == "port" }

    private fun valueParametersOf(facade: String, name: String): List<KParameter> =
        entryPoint(facade, name).parameters.filter { it.kind == KParameter.Kind.VALUE }

    private val backends = listOf(
        "pekko" to "io.github.matthewjones372.pelican.pekko",
        "http4k" to "io.github.matthewjones372.pelican.http4k",
        "ktor" to "io.github.matthewjones372.pelican.ktor",
    )

    @Test
    fun `start reads port then host on every backend, and asks for nothing else`() {
        backends.forEach { (name, pkg) ->
            val parameters = valueParametersOf("$pkg.ServerKt", "start")

            withClue("$name: ${parameters.map { it.name }}") {
                parameters.map { it.name }.take(2) shouldBe listOf("port", "host")
                parameters.all { it.isOptional } shouldBe true
            }
        }
    }

    @Test
    fun `and so does startWithDocs, which is the same call with a page attached`() {
        backends.forEach { (name, pkg) ->
            val parameters = valueParametersOf("$pkg.docs.DocsKt", "startWithDocs")

            withClue("$name: ${parameters.map { it.name }}") {
                parameters.map { it.name }.take(2) shouldBe listOf("port", "host")
                parameters.all { it.isOptional } shouldBe true
            }
        }
    }

    /**
     * Reaching the network is a choice a service spells out, so the default is
     * the one Pekko already had. What is asserted is the address each server
     * was bound to and that it answers there — not that it refuses elsewhere,
     * which depends on what interfaces the machine running this happens to have.
     */
    @Test
    fun `a bare start binds loopback, and answers there`() {
        val pekko = pekkoApi().startOnPekko(port = 0, systemName = "start-parity-pekko")
        val http4k = http4kApi().startOnHttp4k(port = 0)
        val ktor = ktorApi().startOnKtor(port = 0)

        try {
            val bound = mapOf("pekko" to pekko.host, "http4k" to http4k.host, "ktor" to ktor.host)
            withClue("bound: $bound") { bound.values.toSet() shouldBe setOf("127.0.0.1") }

            listOf("pekko" to pekko.baseUrl, "http4k" to http4k.baseUrl, "ktor" to ktor.baseUrl)
                .forEach { (name, baseUrl) ->
                    withClue(name) {
                        baseUrl shouldStartWith "http://127.0.0.1:"
                        apiClient(baseUrl, JacksonCodecs).use { it.response(motd, Unit) shouldHaveStatus 200 }
                    }
                }
        } finally {
            pekko.stop()
            http4k.stop()
            ktor.stop()
        }
    }
}
