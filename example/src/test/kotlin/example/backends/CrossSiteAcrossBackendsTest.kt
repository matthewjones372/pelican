package example.backends

import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** A cookie POST another site makes a browser send is refused on every backend; one with a bearer token is not. */
class CrossSiteAcrossBackendsTest {

    companion object {
        private val running: Map<String, Running> = runnableBackends.associate { it.name to it.start(port = 0) }

        private val clients: Map<String, ApiClient> =
            running.mapValues { (_, server) -> apiClient(server.baseUrl, JacksonCodecs) }

        @JvmStatic
        fun backends(): List<Array<Any>> =
            runnableBackends.map { arrayOf(it.name, clients.getValue(it.name), running.getValue(it.name).baseUrl) }

        @JvmStatic
        @AfterAll
        fun stopAll() {
            clients.values.forEach { it.close() }
            running.values.forEach { it.stop() }
        }

        private const val ELSEWHERE = "https://evil.example"
    }

    private fun ApiClient.note(origin: String): Int {
        val session = visits.start(Identity("ada"))
        return transport.send(
            request(leaveNote, Unit).withHeader("Cookie", "sid=${session.value}").withHeader("Origin", origin),
        ).status
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a cookie POST from another site is a 403`(name: String, client: ApiClient, baseUrl: String) {
        client.note(ELSEWHERE) shouldBe 403
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the same POST from this site goes through`(name: String, client: ApiClient, baseUrl: String) {
        client.note(baseUrl.trimEnd('/')) shouldBe 200
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a bearer POST from another site goes through, since no browser attaches the token`(
        name: String,
        client: ApiClient,
        baseUrl: String,
    ) {
        client.transport.send(
            client.request(refundTip, Tip(5))
                .withHeader("Authorization", "Bearer $TELLER_TOKEN")
                .withHeader("Origin", ELSEWHERE),
        ).shouldHaveStatus(200)
    }
}
