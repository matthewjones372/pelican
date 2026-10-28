package example.backends

import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveHeader
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** One endpoint that requires a role, the same answers from every backend: let in, forbidden, unnamed. */
class RequiresAcrossBackendsTest {

    companion object {
        private val running: Map<String, Running> = runnableBackends.associate { it.name to it.start(port = 0) }

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

    private fun ApiClient.refund(token: String?, body: String = """{"amount":5}"""): ResponseSpec {
        val request: RequestSpec = request(refundTip, Tip(5)).withBody(body)
        return transport.send(token?.let { request.withHeader("Authorization", "Bearer $it") } ?: request)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a teller is let in`(name: String, client: ApiClient) {
        client.refund(TELLER_TOKEN).shouldHaveStatus(200).body shouldContain "-5"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a caller who is not one is refused with 403, naming the role`(name: String, client: ApiClient) {
        client.refund(GUEST_TOKEN).shouldHaveStatus(403).body shouldContain "role `teller`"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the 403 comes before the body is read`(name: String, client: ApiClient) {
        client.refund(TELLER_TOKEN, body = "not json").shouldHaveStatus(400)
        client.refund(GUEST_TOKEN, body = "not json").shouldHaveStatus(403)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a call naming nobody is a 401, with its challenge`(name: String, client: ApiClient) {
        client.refund(token = null).shouldHaveStatus(401).shouldHaveHeader("WWW-Authenticate", "Bearer")
    }
}
