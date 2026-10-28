package example.backends

import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.before
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.github.matthewjones372.pelican.unauthorized
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** `greetingsApi`'s `responseHeaders { }`, on endpoints and on every refusal, the same on every backend. */
class ResponseHeadersAcrossBackendsTest {

    companion object {
        /** Sets a header, then refuses: what it set goes out on the 401. */
        private val knock = before { p ->
            if (p.requestHeader("X-Knock") == "no") {
                p.setRawHeader("X-Knocked", "yes")
                unauthorized()
            }
        }

        private val running: Map<String, Running> =
            runnableBackends.associate { it.name to it.start(port = 0, outerFilters = listOf(knock)) }

        private val clients: Map<String, ApiClient> =
            running.mapValues { (_, server) -> apiClient(server.baseUrl, JacksonCodecs) }

        @JvmStatic
        fun backends(): List<Array<Any>> = runnableBackends.map { arrayOf(it.name, clients.getValue(it.name)) }

        @JvmStatic
        @AfterAll
        fun stopAll() {
            clients.values.forEach { it.close() }
            running.values.forEach { it.stop() }
        }
    }

    private fun ResponseSpec.carriesPolicy(error: Boolean) = withClue("$status $headers") {
        header("X-Content-Type-Options") shouldBe "nosniff"
        header("Cache-Control") shouldBe (if (error) "no-store" else null)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a 200 carries the policy's header`(name: String, client: ApiClient) {
        client.response(greet, In2("ada", false)).shouldHaveStatus(200).carriesPolicy(error = false)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `so does a declared failure`(name: String, client: ApiClient) {
        client.response(echo, In2(null, Note(FLOOD))).shouldHaveStatus(429).carriesPolicy(error = true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `so does a filter's 401, with the header the filter set before it threw`(name: String, client: ApiClient) {
        val res = client.transport.send(client.request(greet, In2("ada", false)).withHeader("X-Knock", "no"))
        res.shouldHaveStatus(401).carriesPolicy(error = true)
        res.header("X-Knocked") shouldBe "yes"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `so does a body that would not decode`(name: String, client: ApiClient) {
        val res = client.transport.send(client.request(echo, In2(null, Note("hi"))).withBody("{not json"))
        res.shouldHaveStatus(400).carriesPolicy(error = true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `so does a 404 for a path no endpoint describes`(name: String, client: ApiClient) {
        val res = client.transport.send(client.request(greet, In2("ada", false)).withPath("/nope"))
        res.shouldHaveStatus(404).carriesPolicy(error = true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `so does a 405 for a method no endpoint declares`(name: String, client: ApiClient) {
        val res = client.transport.send(client.request(greet, In2("ada", false)).withMethod(Method.PATCH))
        res.shouldHaveStatus(405).carriesPolicy(error = true)
    }
}
