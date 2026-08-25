package example.backends

import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * `ServerErrors.described` decides what a throwable becomes on the wire, and it
 * is in core so three interpreters cannot answer one condition three ways. Three
 * of its entries were reached only by code written three times and asserted on
 * one backend at a time, which is the arrangement it exists to prevent.
 */
class RefusalsAcrossBackendsTest {

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
    }

    private fun strictly(client: ApiClient) = client.request(strict, In3("kotlin", "ada", "crumbs"))

    // ------------------------------------------------------------ a body no codec can read

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a body that is not JSON at all is a 400, not a 500`(name: String, client: ApiClient) {
        val res = client.transport.send(
            client.request(echo, In2(null, Note("x"))).withBody("{ nope"),
        )
        withClue(name) {
            res shouldHaveStatus 400
            res.body shouldContain "Malformed request body"
        }
    }

    // ------------------------------------------------------- an input that never arrived

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a required query parameter left off is a 400 naming it`(name: String, client: ApiClient) {
        val req = strictly(client)
        val res = client.transport.send(req.withoutQuery("term"))
        withClue(name) {
            res shouldHaveStatus 400
            res.body shouldContain "term"
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a required header left off is a 400 naming it`(name: String, client: ApiClient) {
        val req = strictly(client)
        val res = client.transport.send(req.withoutHeader("X-Key"))
        withClue(name) {
            res shouldHaveStatus 400
            res.body shouldContain "X-Key"
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a required cookie left off is a 400 naming it`(name: String, client: ApiClient) {
        val req = strictly(client)
        val res = client.transport.send(req.withoutHeader("Cookie"))
        withClue(name) {
            res shouldHaveStatus 400
            res.body shouldContain "jar"
        }
    }

    // ------------------------------------------------------------- nothing it can send

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `an Accept taking nothing this endpoint sends is a 406 naming what it does`(
        name: String,
        client: ApiClient,
    ) {
        val req = strictly(client)
        val res = client.transport.send(req.withHeader("Accept", "text/csv"))
        withClue(name) {
            res shouldHaveStatus 406
            res.body shouldContain "application/json"
        }
    }
}
