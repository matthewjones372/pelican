package example.backends

import io.github.matthewjones372.pelican.before
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.onlyWhen
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** Three cookies set three ways on one answer, and every backend sends all three in the order they were set. */
class SetCookieAcrossBackendsTest {

    companion object {
        private val twoMore = before { p ->
            p.setHeader(sessionSet, "theme=dark; Path=/")
            p.addRawHeader("Set-Cookie", "device=d1; Path=/")
        }.onlyWhen { it === startVisit }

        private val running: Map<String, Running> =
            runnableBackends.associate { it.name to it.start(port = 0, outerFilters = listOf(twoMore)) }

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `every Set-Cookie goes out, in the order it was set`(name: String, client: ApiClient) {
        val res = client.transport.send(client.request(startVisit, "ada-$name")).shouldHaveStatus(200)
        val cookies = res.headers.filter { it.first.equals("Set-Cookie", ignoreCase = true) }.map { it.second }

        withClue(res.headers.toString()) {
            cookies shouldHaveSize 3
            cookies[0] shouldBe "theme=dark; Path=/"
            cookies[1] shouldBe "device=d1; Path=/"
            cookies[2] shouldStartWith "sid="
        }
    }
}
