package example.backends

import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.shouldHaveContentType
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * `ServerErrors.described` decides what a throwable becomes on the wire, and it
 * is in core so three interpreters cannot answer one condition three ways. Three
 * of its entries were reached only by code written three times and asserted on
 * one backend at a time, which is the arrangement it exists to prevent.
 *
 * Since a service picks the envelope its refusals are written in, the suite runs
 * once per shipped renderer as well as once per backend. Which fields carry the
 * status and the sentence is the [Dialect]'s business; that all six answer the
 * same thing is this file's.
 */
class RefusalsAcrossBackendsTest {

    companion object {
        private val running: Map<Pair<String, String>, Running> = allBackends.flatMap { backend ->
            allDialects.map { dialect ->
                (backend.name to dialect.name) to backend.start(port = 0, refusals = dialect.renderer)
            }
        }.toMap()

        private val clients: Map<Pair<String, String>, ApiClient> =
            running.mapValues { (_, server) -> apiClient(server.baseUrl, JacksonCodecs) }

        @JvmStatic
        fun backends(): List<Array<Any>> = allBackends.flatMap { backend ->
            allDialects.map { dialect ->
                arrayOf<Any>("${backend.name}/${dialect.name}", clients.getValue(backend.name to dialect.name), dialect)
            }
        }

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
    fun `a body that is not JSON at all is a 400, not a 500`(name: String, client: ApiClient, dialect: Dialect) {
        val res = client.transport.send(
            client.request(echo, In2(null, Note("x"))).withBody("{ nope"),
        )
        withClue(name) {
            res shouldHaveStatus 400
            dialect.status(res) shouldBe 400
            dialect.reason(res) shouldBe "Malformed request body"
        }
    }

    // ------------------------------------------------------- an input that never arrived

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a required query parameter left off is a 400 naming it`(
        name: String,
        client: ApiClient,
        dialect: Dialect,
    ) {
        val req = strictly(client)
        val res = client.transport.send(req.withoutQuery("term"))
        withClue(name) {
            res shouldHaveStatus 400
            dialect.says(res) shouldContain "term"
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a required header left off is a 400 naming it`(name: String, client: ApiClient, dialect: Dialect) {
        val req = strictly(client)
        val res = client.transport.send(req.withoutHeader("X-Key"))
        withClue(name) {
            res shouldHaveStatus 400
            dialect.says(res) shouldContain "X-Key"
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a required cookie left off is a 400 naming it`(name: String, client: ApiClient, dialect: Dialect) {
        val req = strictly(client)
        val res = client.transport.send(req.withoutHeader("Cookie"))
        withClue(name) {
            res shouldHaveStatus 400
            dialect.says(res) shouldContain "jar"
        }
    }

    // ------------------------------------------------------------- nothing it can send

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `an Accept taking nothing this endpoint sends is a 406 naming what it does`(
        name: String,
        client: ApiClient,
        dialect: Dialect,
    ) {
        val req = strictly(client)
        val res = client.transport.send(req.withHeader("Accept", "text/csv"))
        withClue(name) {
            res shouldHaveStatus 406
            dialect.says(res) shouldContain "application/json"
        }
    }

    // --------------------------------------------------------- the dialect on the wire

    /**
     * A refusal is labelled with the media type its renderer names, not with the
     * one the endpoint would have answered with. RFC 9457 exists to be
     * recognised by a client, which it cannot be under `application/json`.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a refusal carries the configured envelope's media type`(
        name: String,
        client: ApiClient,
        dialect: Dialect,
    ) {
        val res = client.transport.send(strictly(client).withoutQuery("term"))

        withClue(name) { res shouldHaveContentType dialect.mediaType }
    }

    /** A refusal raised before any route matched is still the service's own dialect. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `and so does one refused before a route was chosen`(name: String, client: ApiClient, dialect: Dialect) {
        val res = client.transport.send(
            client.request(echo, In2("trace-1", Note("hi")))
                .withMethod(Method.OPTIONS)
                .withBody(null)
                .withHeader("Origin", "https://not.allowed.example")
                .withHeader("Access-Control-Request-Method", Method.POST.name),
        )

        withClue("$name: ${res.status} ${res.body}") {
            res shouldHaveStatus 403
            res shouldHaveContentType dialect.mediaType
            dialect.reason(res) shouldBe "Forbidden"
        }
    }
}
