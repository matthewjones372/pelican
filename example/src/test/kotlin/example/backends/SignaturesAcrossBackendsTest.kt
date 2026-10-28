package example.backends

import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.github.matthewjones372.pelican.test.signedWith
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.temporal.ChronoUnit

/** A signed webhook, the same answers from every backend: a signed delivery is read, a forged or stale one refused. */
class SignaturesAcrossBackendsTest {

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

    private fun ApiClient.notice(amount: Int) = request(tipNoticed, Tip(amount))

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a delivery signed as the sender signs it is read`(name: String, client: ApiClient) {
        val signed = client.notice(5).signedWith(tipHooks, TIP_HOOK_SECRET)

        client.transport.send(signed).shouldHaveStatus(200).body shouldBe "noticed a tip of 5"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a forged, a stale and an unsigned delivery are each a 400 naming no cause`(name: String, client: ApiClient) {
        val forged = client.notice(5).signedWith(tipHooks, "whsec_bm90IHRoZSBqYXIncyBzZWNyZXQ=")
        val stale = client.notice(5).signedWith(tipHooks, TIP_HOOK_SECRET, Instant.now().minus(6, ChronoUnit.MINUTES))

        listOf(forged, stale, client.notice(5)).forEach { delivery ->
            val refused = client.transport.send(delivery).shouldHaveStatus(400)
            refused.body shouldContain "\"error\":\"signature_invalid\""
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a body altered after signing is refused`(name: String, client: ApiClient) {
        val signed = client.notice(5).signedWith(tipHooks, TIP_HOOK_SECRET)
        val altered = client.notice(500).let { other ->
            signed.headers.fold(other) { request, (header, value) -> request.withHeader(header, value) }
        }

        client.transport.send(altered).shouldHaveStatus(400)
    }
}
