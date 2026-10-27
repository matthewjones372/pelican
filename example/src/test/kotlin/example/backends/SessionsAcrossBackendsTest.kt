package example.backends

import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import kotlin.time.Duration.Companion.minutes

/** A session cookie, the same answers from every backend: set, read back, and a 401 once revoked or expired. */
class SessionsAcrossBackendsTest {

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

    @AfterEach
    fun onTime() = VisitClock.reset()

    private fun ApiClient.me(cookie: String?): ResponseSpec {
        val asked = request(whoIsVisiting, Unit)
        return transport.send(cookie?.let { asked.withHeader("Cookie", it) } ?: asked)
    }

    private fun cookieOf(subject: String): String = visits.start(Identity(subject)).let { "${it.name}=${it.value}" }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a visit sets a safe session cookie, and the cookie is who calls next`(name: String, client: ApiClient) {
        val started = client.transport.send(client.request(startVisit, "ada-$name")).shouldHaveStatus(200)
        val setCookie = started.header("Set-Cookie")!!

        setCookie shouldMatch Regex("sid=[A-Za-z0-9_-]{43}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=43200")
        client.me(setCookie.substringBefore(';')).shouldHaveStatus(200).body shouldBe "ada-$name"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `no cookie is a 401`(name: String, client: ApiClient) {
        client.me(cookie = null).shouldHaveStatus(401)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a revoked session is a 401`(name: String, client: ApiClient) {
        val cookie = cookieOf("revoked-$name")
        client.me(cookie).shouldHaveStatus(200)

        visits.endAll("revoked-$name")

        client.me(cookie).shouldHaveStatus(401)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `an expired session is a 401`(name: String, client: ApiClient) {
        val cookie = cookieOf("idle-$name")

        VisitClock.advance(31.minutes)

        client.me(cookie).shouldHaveStatus(401)
    }
}
