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
import io.github.matthewjones372.pelican.pekko.start as startOnPekko

/**
 * The call a user types first, pinned to one shape whichever backend publishes
 * it.
 *
 * `AllBackendsTest` asks a running server its questions; nothing there notices
 * that starting one used to be spelled a different way per backend, or that a
 * bare `start()` reached the network on one of them and loopback on another.
 * A returning backend is held to the shape by adding a row to [backends].
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
     * the one Pekko already had. What is asserted is the address the server was
     * bound to and that it answers there — not that it refuses elsewhere, which
     * depends on what interfaces the machine running this happens to have.
     */
    @Test
    fun `a bare start binds loopback, and answers there`() {
        val pekko = pekkoApi().startOnPekko(port = 0, systemName = "start-parity-pekko")

        try {
            val bound = mapOf("pekko" to pekko.host)
            withClue("bound: $bound") { bound.values.toSet() shouldBe setOf("127.0.0.1") }

            listOf("pekko" to pekko.baseUrl)
                .forEach { (name, baseUrl) ->
                    withClue(name) {
                        baseUrl shouldStartWith "http://127.0.0.1:"
                        apiClient(baseUrl, JacksonCodecs).use { it.response(motd, Unit) shouldHaveStatus 200 }
                    }
                }
        } finally {
            pekko.stop()
        }
    }
}
