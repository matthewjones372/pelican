package example.backends

import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.errorBody
import io.github.matthewjones372.pelican.test.shouldBeApiError
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * What a handler that simply breaks tells the caller.
 *
 * `renderError` decides it in core, and each interpreter then builds the
 * response from what it hands back at a call site of its own — three of them,
 * none previously asserted against another. The answer is the one thing a
 * production team reads at three in the morning, so it is asked of all three.
 */
class ThrowingHandlerTest {

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

        /** Twelve hex characters, which is what `renderError` mints. */
        private val REFERENCE = Regex("Reference: [0-9a-f]{12}")
    }

    private fun broken(client: ApiClient) = client.response(echo, In2(null, Note(BOOM)))

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a handler that throws answers the ApiError shape, as JSON, with a 500`(name: String, client: ApiClient) {
        withClue(name) { client.shouldBeApiError(broken(client), 500, "Internal server error") }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `and its detail is a reference to grep the log for`(name: String, client: ApiClient) {
        val detail = client.errorBody(broken(client)).detail

        withClue(name) { detail.shouldNotBeNull() shouldMatch REFERENCE }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a new reference each time, so two failures are two lines in the log`(name: String, client: ApiClient) {
        val references = List(2) { client.errorBody(broken(client)).detail }

        withClue("$name: $references") { references.toSet().size shouldBe 2 }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `and nothing of the throwable itself reaches the caller`(name: String, client: ApiClient) {
        val body = broken(client).body

        withClue("$name: $body") {
            body shouldNotContain BOOM_DETAIL
            body shouldNotContain "Exception"
            body shouldNotContain "example.backends"
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the header a filter stamped is still on the answer`(name: String, client: ApiClient) {
        // Which is what makes the reference worth printing: the caller can
        // quote both, and one of them is in the access log too.
        val res = broken(client)

        withClue("$name: ${res.headers}") { res.header("X-Request-Id").shouldNotBeNull() }
    }
}
