package example.backends

import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveNoBody
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * HEAD, which every router maps and nothing asked for until now.
 *
 * Two different questions, and only the first is Pelican's: a *declared* HEAD
 * endpoint is a route like any other and has to answer everywhere. Whether an
 * engine answers HEAD for a GET endpoint that never declared one is the
 * engine's own arrangement, so that one is pinned per backend rather than
 * asserted across them — see [MethodMismatchTest] for the same treatment of the
 * same kind of difference.
 */
class HeadRequestsTest {

    companion object {
        private val running: Map<String, Running> = allBackends.associate { it.name to it.start(port = 0) }

        private val clients: Map<String, ApiClient> =
            running.mapValues { (_, server) -> apiClient(server.baseUrl, JacksonCodecs) }

        @JvmStatic
        fun backends(): List<Array<Any>> = allBackends.map { arrayOf(it.name, clients.getValue(it.name)) }

        @JvmStatic
        @AfterAll
        fun stopAll() {
            clients.values.forEach { it.close() }
            running.values.forEach { it.stop() }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a declared HEAD endpoint answers, with headers and no body`(name: String, client: ApiClient) {
        val res = client.response(peek, Unit)

        withClue(name) {
            res shouldHaveStatus 200
            res.shouldHaveNoBody()
            // The whole answer a HEAD carries, and the reason to declare one.
            withClue(res.headers.toString()) { res.header("X-Request-Id").shouldNotBeNull() }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `and the request line it builds is the one the description publishes`(name: String, client: ApiClient) {
        withClue(name) { client.request(peek, Unit).toString() shouldBe "HEAD /peek" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `HEAD against a GET endpoint is whatever the engine already does`(name: String, client: ApiClient) {
        val res = client.transport.send(client.request(motd, Unit).withMethod(Method.HEAD))

        withClue("$name answered ${res.status}") { res.status shouldBe pinned.getValue(name) }
    }

    /**
     * A recorded difference, not a rule. Nothing in Pelican asks an engine to
     * answer a method no endpoint declared, and normalising engines is a
     * behaviour change that deserves deciding on its own; this is the evidence
     * it would be decided with.
     *
     * Pekko ships `transparent-head-requests = off`, so nothing rewrites a HEAD
     * into the GET beside it. This API declares a HEAD endpoint, so the HEAD
     * route matches on method and rejects on path, and that rejection swallows
     * the other routes' method rejections — the mechanism [MethodMismatchTest]
     * documents.
     */
    private val pinned = mapOf(
        "pekko" to 404,
    )
}
