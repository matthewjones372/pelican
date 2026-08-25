package example.backends

import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.metrics.metrics
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The claim the whole design rests on, asked of all three interpreters at once:
 * the status a filter is told is the status the caller was sent.
 *
 * Working out that status is core's job — `Endpoint.statusFor` — precisely so
 * that it is not three jobs. A filter cannot see the response the interpreter
 * renders after the chain has unwound, so it reads the answer off the
 * description instead, and the risk that buys is drift: the description says
 * 201 and Pekko sends 200, and the graph is wrong in a way nobody notices for a
 * quarter. This suite is what makes that drift a build failure. Each case drives
 * one request, reads the status off the socket, and asks the registry what it
 * recorded — on Pekko, on http4k and on Ktor.
 *
 * The four cases are the four ways a request ends that a filter can see: a
 * plain success, a success the handler *named* out of several declared, a
 * declared failure returned as a value, and a refusal thrown by a filter
 * further in. What is deliberately not here is a request that never reached the
 * chain at all — a path parameter that would not decode, a body over the limit
 * — because those are answered before a filter is asked, and no filter-based
 * metric can see them. `docs/reference.md` says so where it describes the
 * module rather than leaving it to be discovered from a flat line on a graph.
 */
class MetricsAcrossBackendsTest {

    companion object {
        private val registries: Map<String, MeterRegistry> =
            allBackends.associate { it.name to SimpleMeterRegistry() }

        private val running: Map<String, Running> = allBackends.associate { backend ->
            backend.name to backend.start(
                port = 0,
                // Outermost, so that the 403 raised by `gate` is counted too.
                outerFilters = listOf(metrics(registries.getValue(backend.name))),
            )
        }

        private val clients: Map<String, ApiClient> =
            running.mapValues { (_, server) -> apiClient(server.baseUrl, JacksonCodecs) }

        @JvmStatic
        fun backends(): List<Array<Any>> =
            allBackends.map { arrayOf(it.name, clients.getValue(it.name)) }

        @JvmStatic
        @AfterAll
        fun stopAll() {
            clients.values.forEach { it.close() }
            running.values.forEach { it.stop() }
        }
    }

    /** The (path, status) pairs the request counter is holding for one backend. */
    private fun recorded(name: String): Set<Pair<String?, String?>> =
        registries.getValue(name).meters
            .filter { it.id.name == "http.server.requests" }
            .map { it.id.getTag("path") to it.id.getTag("status") }
            .toSet()

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `what the meters recorded is what the socket answered`(name: String, client: ApiClient) {
        // One request per way of ending, driven through the typed client so
        // that all three backends are asked exactly the same thing.
        val answered = listOf(
            // A plain success.
            "/hello/{name}" to client.response(greet, In2("ada", false)).status,
            // The second of two declared successes, named by the handler: a
            // 201, which nothing about the returned value would reveal.
            "/greetings/{name}" to client.response(remember, In2("newcomer", Note("hi"))).status,
            // A declared failure, returned rather than thrown. Its status is on
            // the declaration and nowhere else.
            "/echo" to client.response(echo, In2(null, Note(FLOOD))).status,
            // A refusal from `gate`, which throws where it stands rather than
            // failing a stage — the ending a filter chain drops if it is
            // watching for a failed stage alone.
            "/echo" to client.response(echo, In2("blocked", Note("never seen"))).status,
        )

        withClue("the statuses on the wire are not the ones this suite is written about") {
            answered.map { it.second } shouldBe listOf(200, 201, 429, 403)
        }

        withClue("the meters disagree with the responses on $name") {
            recorded(name) shouldBe answered.map { (path, status) -> path to status.toString() }.toSet()
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the dimensions are the description's, identically on every backend`(name: String, client: ApiClient) {
        client.response(greet, In2("ada", false))

        val tags = registries.getValue(name).meters
            .single { it.id.name == "http.server.requests" && it.id.getTag("path") == "/hello/{name}" }
            .id.tags.associate { it.key to it.value }

        tags shouldBe mapOf(
            "method" to "GET",
            "path" to "/hello/{name}",
            "operation" to "greet",
            "status" to "200",
            "deprecated" to "false",
        )
    }
}
