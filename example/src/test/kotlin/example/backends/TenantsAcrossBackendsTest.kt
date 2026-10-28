package example.backends

import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** A tenanted endpoint, the same answers from every backend: the shop it is for, and the 400, 403 and 404. */
class TenantsAcrossBackendsTest {

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

    private fun ApiClient.till(shop: String?, token: String = TELLER_TOKEN): ResponseSpec {
        val asked = request(openTill, Unit).withHeader("Authorization", "Bearer $token")
        return transport.send(shop?.let { asked.withHeader("X-Shop", it) } ?: asked)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the shop the request names is the one the handler is handed`(name: String, client: ApiClient) {
        client.till("acme").shouldHaveStatus(200).body shouldBe "tess at acme"
        client.till("globex", token = GUEST_TOKEN).shouldHaveStatus(200).body shouldBe "gus at globex"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `no shop is a 400 and an unknown one a 404`(name: String, client: ApiClient) {
        client.till(shop = null).shouldHaveStatus(400)
        client.till("initech").shouldHaveStatus(404)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a teller of one shop is refused in another`(name: String, client: ApiClient) {
        client.till("globex").shouldHaveStatus(403)
    }
}
