package example.backends

import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.apiClient
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * What a browser is told, asked of all three backends.
 *
 * Companion to [AllBackendsTest], and the same argument: the policy lives in
 * core and is derived from the endpoint descriptions, so three interpreters
 * that disagree here are two interpreters with a bug. `CorsPolicyTest` in
 * `pelican-core` holds the decisions themselves; this holds the claim that each
 * backend puts them on the wire — on a preflight, on a success, and on an
 * error, which is the one a hand-rolled filter usually misses.
 *
 * The requests are still built from the endpoint values. A browser's own
 * headers are not part of any description, so they go on by hand with
 * `withHeader` — which is exactly what a browser does to a request the script
 * never wrote them into.
 */
class CorsTest {

    companion object {
        private const val ALLOWED = "https://console.example.com"
        private const val REJECTED = "https://not-the-console.example.com"

        private val running: Map<String, Running> =
            allBackends.associate { it.name to it.start(port = 0) }

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

    /** The `OPTIONS` a browser sends before a POST it is not sure it may make. */
    private fun ApiClient.preflight(from: String, method: Method = Method.POST): ResponseSpec =
        transport.send(
            request(echo, In2("trace-1", Note("hi")))
                .withMethod(Method.OPTIONS)
                .withBody(null)
                .withHeader("Origin", from)
                .withHeader("Access-Control-Request-Method", method.name),
        )

    private fun RequestSpec.fromBrowser(origin: String = ALLOWED): RequestSpec =
        withHeader("Origin", origin)

    // -------------------------------------------------------------- preflight

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a preflight is answered from the description of that path`(name: String, client: ApiClient) {
        val res = client.preflight(from = ALLOWED)

        res.status shouldBe 204
        res.header("Access-Control-Allow-Origin") shouldBe ALLOWED
        res.header("Access-Control-Allow-Methods") shouldBe "POST"
        res.header("Access-Control-Max-Age") shouldBe "600"
    }

    /**
     * The whole point of deriving this: `X-Trace-Id` and the JSON body are
     * declared on `echo` and nowhere else, and that is enough to let a browser
     * send both.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the headers a browser may send are the ones the endpoint declares`(name: String, client: ApiClient) {
        client.preflight(from = ALLOWED).header("Access-Control-Allow-Headers") shouldBe "X-Trace-Id, Content-Type"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `an origin the API does not name is refused, with nothing leaked`(name: String, client: ApiClient) {
        val res = client.preflight(from = REJECTED)

        res.status shouldBe 403
        res.header("Access-Control-Allow-Origin").shouldBeNull()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a method that path never describes is refused`(name: String, client: ApiClient) {
        val res = client.preflight(from = ALLOWED, method = Method.DELETE)

        res.status shouldBe 403
        res.body shouldContain "DELETE"
    }

    // ----------------------------------------------------------- real requests

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a cross-origin call is answered with the header that lets a script read it`(
        name: String,
        client: ApiClient,
    ) {
        val res = client.transport.send(client.request(echo, In2("trace-1", Note("hi"))).fromBrowser())

        res.status shouldBe 200
        res.body shouldBe """{"text":"hi","trace":"trace-1"}"""
        res.header("Access-Control-Allow-Origin") shouldBe ALLOWED
        res.header("Vary") shouldBe "Origin"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a streamed response carries them too`(name: String, client: ApiClient) {
        val res = client.transport.send(client.request(countdown, 2).fromBrowser())

        res.header("Access-Control-Allow-Origin") shouldBe ALLOWED
    }

    /**
     * Without this the script sees a bare network error instead of the 400 the
     * server took the trouble to explain.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `an error carries them, or the browser hides why the call failed`(name: String, client: ApiClient) {
        val res = client.transport.send(
            client.request(echo, In2("trace-1", Note("hi"))).withBody("not json").fromBrowser(),
        )

        res.status shouldBe 400
        res.header("Access-Control-Allow-Origin") shouldBe ALLOWED
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `an origin the API does not name is told nothing at all`(name: String, client: ApiClient) {
        val res = client.transport.send(
            client.request(echo, In2("trace-1", Note("hi"))).fromBrowser(REJECTED),
        )

        // The call itself is served — CORS is a browser's rule, not a
        // credential check, and pretending otherwise would make a `curl` and a
        // fetch behave differently. What is withheld is the permission to read
        // the answer.
        res.status shouldBe 200
        res.header("Access-Control-Allow-Origin").shouldBeNull()
        res.header("Vary") shouldBe "Origin"
    }

    /**
     * The one header a request with no `Origin` still picks up, and it has to:
     * a cache between the browser and the service would otherwise be free to
     * store this answer under the URL alone and hand it back — stripped of its
     * `Access-Control-Allow-Origin` — to the browser that was allowed to read
     * it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a request that is not cross-origin is left as it was, but still varies by origin`(
        name: String,
        client: ApiClient,
    ) {
        val res = client.transport.send(client.request(echo, In2("trace-1", Note("hi"))))

        res.status shouldBe 200
        res.header("Access-Control-Allow-Origin").shouldBeNull()
        res.header("Vary") shouldBe "Origin"
    }

    // ------------------------------------------------------------ and together

    @Test
    fun `all three answer a preflight identically`() {
        val answers = clients.mapValues { (_, client) ->
            val res = client.preflight(from = ALLOWED)
            res.status to listOf(
                "Access-Control-Allow-Origin",
                "Access-Control-Allow-Methods",
                "Access-Control-Allow-Headers",
                "Access-Control-Max-Age",
                "Vary",
            ).map { it to res.header(it) }
        }

        withClue("backends disagreed: $answers") { answers.values.toSet().size shouldBe 1 }
    }
}
