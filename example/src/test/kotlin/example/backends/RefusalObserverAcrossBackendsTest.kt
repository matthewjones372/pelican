package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.InMemoryClientTransport
import io.github.matthewjones372.pelican.RefusalObserver
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.Transport
import io.github.matthewjones372.pelican.test.apiClient
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The counter's parity claim: one refusal, four transports, one observation.
 *
 * `http.server.requests` is recorded by a filter, and a filter cannot be asked
 * about the requests that never reach it. This hook is called from the one
 * function that turns a refusal into a response — core's `renderError`, which
 * every interpreter and the in-memory transport already answer through — so
 * parity here is not several implementations agreeing but one implementation
 * being reached several ways. What this suite proves is that the *route in* is
 * the same on each: the same request refused at the same point, carrying the
 * same template.
 */
class RefusalObserverAcrossBackendsTest {

    companion object {
        private const val IN_MEMORY = "in-memory"

        /** What each transport's observer was told, in the order it was told. */
        private val observed: Map<String, MutableList<String>> =
            (allBackends.map { it.name } + IN_MEMORY).associateWith { mutableListOf() }

        private fun recorder(name: String): RefusalObserver {
            val into = observed.getValue(name)
            return RefusalObserver { reason, status, pathTemplate ->
                synchronized(into) { into += "${reason.label} $status ${pathTemplate ?: "-"}" }
            }
        }

        private val running: Map<String, Running> = allBackends.associate { backend ->
            backend.name to backend.start(port = 0, onRefusal = recorder(backend.name))
        }

        private val clients: Map<String, ApiClient> =
            running.mapValues { (_, server) -> apiClient(server.baseUrl, JacksonCodecs) } +
                (IN_MEMORY to ApiClient(OverInMemory(pekkoApi(onRefusal = recorder(IN_MEMORY))), JacksonCodecs))

        @JvmStatic
        fun transports(): List<Array<Any>> =
            clients.map { (name, client) -> arrayOf(name, client) }

        @JvmStatic
        @AfterAll
        fun stopAll() {
            clients.values.forEach { it.close() }
            running.values.forEach { it.stop() }
        }
    }

    private fun seen(name: String): List<String> = observed.getValue(name).toList()

    /**
     * One request per reason a refusal can carry, driven through the typed
     * client so that four transports are asked exactly the same thing.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("transports")
    fun `the same refusals are observed the same way whichever transport answers`(
        name: String,
        client: ApiClient,
    ) {
        val answered = listOf(
            // A path capture that will not decode. The route is still being
            // chosen when it fails, but the index knows which one it had
            // matched, so the refusal is reported under that template.
            client.transport.send(client.request(countdown, 3).withPath("/countdown/not-a-number")).status,
            // A body no codec can read, on a route that did match.
            client.transport.send(client.request(echo, In2(null, Note("x"))).withBody("{ nope")).status,
            // An `Accept` taking nothing this endpoint sends.
            client.transport.send(
                client.request(strict, In3("kotlin", "ada", "crumbs"))
                    .withHeader("Accept", "text/csv"),
            ).status,
            // A `Content-Type` no alternative on this endpoint reads.
            client.transport.send(
                client.request(signIn, SignIn("ada", remember = true, visits = 3))
                    .withHeader("Content-Type", "application/xml").withBody("<signIn/>"),
            ).status,
            // A body past `maxBodyBytes`, refused before any codec sees it.
            client.transport.send(client.request(echo, In2(null, Note("x"))).withBody(oversize())).status,
        )

        withClue("the statuses on the wire are not the ones this suite is written about") {
            answered shouldBe listOf(400, 400, 406, 415, 413)
        }

        withClue("what $name observed") {
            seen(name) shouldBe listOf(
                "decode 400 /countdown/{from}",
                "decode 400 /echo",
                "accept 406 /strict",
                "content_type 415 /sign-in",
                "body_limit 413 /echo",
            )
        }
    }

    /**
     * The one reason a bound backend cannot report, said out loud rather than
     * left to be noticed as a flat line on a graph.
     *
     * A path nothing describes is handed back to the server library's own
     * router — a Pekko rejection — which is what lets a Pelican route be
     * mounted beside routes written by hand.
     * Nothing renders that 404, so nothing observes it either. The in-memory
     * transport has no router underneath to decline to, so it answers the
     * refusal itself and does count it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("transports")
    fun `a path nothing describes is observed only where Pelican answers it itself`(
        name: String,
        client: ApiClient,
    ) {
        observed.getValue(name).clear()

        val res = client.transport.send(client.request(countdown, 3).withPath("/nothing-here"))

        withClue("$name answered ${res.status}") { res.status shouldBe 404 }

        if (name == IN_MEMORY) seen(name) shouldBe listOf("unmatched 404 -")
        else withClue("the router answered, so no refusal was rendered") { seen(name).shouldBeEmpty() }
    }

    /** Four kilobytes is the limit `greetingsApi` sets; this is comfortably past it. */
    private fun oversize(): String = """{"text":"${"x".repeat(8_192)}"}"""
}

/**
 * `InMemoryClientTransport` as a test client's transport. It is a
 * [io.github.matthewjones372.pelican.ClientTransport] — what a generated client
 * takes — and this suite drives the same requests through it as through a
 * socket.
 */
private class OverInMemory(api: Api) : Transport {

    private val transport = InMemoryClientTransport(api)

    override fun send(request: RequestSpec): ResponseSpec {
        val body = request.body
        val answer = transport.send(
            ClientRequest(
                request.method,
                "http://in-memory" + request.target,
                request.headers,
                if (body == null) ClientRequest.Body.Empty else ClientRequest.Body.Text(body),
            ),
        ).toCompletableFuture().join()

        return ResponseSpec(answer.status, answer.headers, answer.text())
    }
}
