package example.backends

import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** A rate-limited endpoint, the same answers from every backend: two through, then a 429 naming when to come back. */
class RateLimitsAcrossBackendsTest {

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

    private fun ApiClient.stamp(token: String): ResponseSpec =
        transport.send(request(stampTicket, Unit).withHeader("Authorization", "Bearer $token"))

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a teller's third ticket in a minute is a 429 with Retry-After, and the refusal envelope says why`(
        name: String,
        client: ApiClient,
    ) {
        repeat(2) { client.stamp(TELLER_TOKEN).shouldHaveStatus(200).body shouldBe "stamped for tess" }

        val refused = client.stamp(TELLER_TOKEN).shouldHaveStatus(429)

        refused.header("Retry-After") shouldBe "30"
        refused.body shouldContain "\"error\":\"rate_limited\""
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `another caller has a budget of their own`(name: String, client: ApiClient) {
        repeat(2) { client.stamp(GUEST_TOKEN).shouldHaveStatus(200).body shouldBe "stamped for gus" }
    }
}
