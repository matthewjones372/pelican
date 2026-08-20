package example.backends

import dev.pelican.In2
import dev.pelican.Method
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.test.ApiClient
import dev.pelican.test.RequestSpec
import dev.pelican.test.ResponseSpec
import dev.pelican.test.apiClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

        assertEquals(204, res.status)
        assertEquals(ALLOWED, res.header("Access-Control-Allow-Origin"))
        assertEquals("POST", res.header("Access-Control-Allow-Methods"))
        assertEquals("600", res.header("Access-Control-Max-Age"))
    }

    /**
     * The whole point of deriving this: `X-Trace-Id` and the JSON body are
     * declared on `echo` and nowhere else, and that is enough to let a browser
     * send both.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the headers a browser may send are the ones the endpoint declares`(name: String, client: ApiClient) {
        assertEquals(
            "X-Trace-Id, Content-Type",
            client.preflight(from = ALLOWED).header("Access-Control-Allow-Headers"),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `an origin the API does not name is refused, with nothing leaked`(name: String, client: ApiClient) {
        val res = client.preflight(from = REJECTED)

        assertEquals(403, res.status)
        assertNull(res.header("Access-Control-Allow-Origin"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a method that path never describes is refused`(name: String, client: ApiClient) {
        val res = client.preflight(from = ALLOWED, method = Method.DELETE)

        assertEquals(403, res.status)
        assertTrue("DELETE" in res.body, res.body)
    }

    // ----------------------------------------------------------- real requests

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a cross-origin call is answered with the header that lets a script read it`(
        name: String,
        client: ApiClient,
    ) {
        val res = client.transport.send(client.request(echo, In2("trace-1", Note("hi"))).fromBrowser())

        assertEquals(200, res.status)
        assertEquals("""{"text":"hi","trace":"trace-1"}""", res.body)
        assertEquals(ALLOWED, res.header("Access-Control-Allow-Origin"))
        assertEquals("Origin", res.header("Vary"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a streamed response carries them too`(name: String, client: ApiClient) {
        val res = client.transport.send(client.request(countdown, 2).fromBrowser())

        assertEquals(ALLOWED, res.header("Access-Control-Allow-Origin"))
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

        assertEquals(400, res.status)
        assertEquals(ALLOWED, res.header("Access-Control-Allow-Origin"))
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
        assertEquals(200, res.status)
        assertNull(res.header("Access-Control-Allow-Origin"))
        assertEquals("Origin", res.header("Vary"))
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

        assertEquals(200, res.status)
        assertNull(res.header("Access-Control-Allow-Origin"))
        assertEquals("Origin", res.header("Vary"))
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

        assertEquals(1, answers.values.toSet().size, "backends disagreed: $answers")
    }
}
