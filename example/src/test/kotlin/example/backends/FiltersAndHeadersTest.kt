package example.backends

import dev.pelican.In2
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.openapi.openApiJson
import dev.pelican.test.ApiClient
import dev.pelican.test.apiClient
import dev.pelican.test.shouldHaveHeader
import dev.pelican.test.shouldHaveStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The three things that used to have nowhere to live, held to one answer across
 * all three interpreters: a response header that is declared rather than
 * stringly-typed, a filter that runs around every handler, and a body size the
 * service refuses before a codec sees it.
 *
 * Same shape as [AllBackendsTest], and for the same reason — a rule written on
 * the `Api` that two backends applied differently would be a rule one of them
 * was getting wrong.
 */
class FiltersAndHeadersTest {

    companion object {
        private val running: Map<String, Running> =
            allBackends.associate { it.name to it.start(port = 0) }

        private val clients: Map<String, ApiClient> =
            running.mapValues { (_, server) -> apiClient(server.baseUrl, JacksonCodecs) }

        @JvmStatic
        fun backends(): List<Array<Any>> =
            allBackends.map { arrayOf(it.name, clients.getValue(it.name)) }

        @JvmStatic
        @AfterAll
        fun stopAll() {
            clients.values.forEach { it.close() }
            running.values.forEach { it.stop() }
        }
    }

    // ------------------------------------------------------- response headers

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a declared response header reaches the wire`(name: String, client: ApiClient) {
        // Nothing in any handler mentions this header. One filter sets it, for
        // every endpoint, through the same value the endpoints declared.
        val res = client.response(greet, In2("ada", false)).shouldHaveStatus(200)
        val id = res.header("X-Request-Id")
        assertTrue(id != null && id.startsWith("gen-"), "no generated id in ${res.headers}")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the filter can read a request header and answer with it`(name: String, client: ApiClient) {
        val req = client.request(echo, In2("trace-me", Note("hi")))
        client.transport.send(req).shouldHaveHeader("X-Request-Id", "trace-me")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a streamed response carries its headers too`(name: String, client: ApiClient) {
        // Worth its own case: a streaming backend commits the status and
        // headers with the first frame, so a header set by a filter has to be
        // on before any element is encoded.
        val res = client.response(countdown, 3)
        res shouldHaveStatus 200
        assertTrue(res.header("X-Request-Id") != null, res.headers.toString())
    }

    // -------------------------------------------------------------- filters

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a filter that refuses is a 403, and the handler never runs`(name: String, client: ApiClient) {
        val res = client.response(echo, In2("blocked", Note("should not be echoed")))
        res shouldHaveStatus 403
        assertFalse(res.body.contains("should not be echoed"), res.body)
        assertTrue(res.body.contains("refused by the gate"), res.body)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `filters run outermost first, so a refusal still gets stamped`(name: String, client: ApiClient) {
        // `stamping` is listed before `gate`, so it has already set the header
        // by the time `gate` throws — and the header survives onto the error.
        // A correlation id that vanished exactly when something went wrong
        // would be a correlation id worth nothing.
        client.response(echo, In2("blocked", Note("nope")))
            .shouldHaveStatus(403)
            .shouldHaveHeader("X-Request-Id", "blocked")
    }

    // ------------------------------------------------------- body size limit

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a body over the limit is refused before it is decoded`(name: String, client: ApiClient) {
        val huge = client.request(echo, In2(null, Note("x".repeat(8_000))))
        val res = client.transport.send(huge)

        res shouldHaveStatus 413
        assertTrue(res.body.contains("Payload too large"), res.body)
        // Decoding never happened, so nothing about the payload comes back.
        assertFalse(res.body.contains("xxxx"), res.body.take(200))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a body under the limit is unaffected`(name: String, client: ApiClient) {
        val fine = client.call(echo, In2(null, Note("x".repeat(100))))
        assertEquals(100, fine.text.length)
    }

    // ------------------------------------------------------------ the document

    @Test
    fun `the declared response header is documented on every response that carries it`() {
        val spec = Json.parseToJsonElement(
            pekkoApi().spec().openApiJson(),
        ).jsonObject

        val header = spec["paths"]!!.jsonObject["/hello/{name}"]!!.jsonObject["get"]!!.jsonObject["responses"]!!
            .jsonObject["200"]!!.jsonObject["headers"]!!.jsonObject["X-Request-Id"]!!.jsonObject

        assertEquals(true, header["required"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            "Correlates this answer with the server's log",
            header["description"]!!.jsonPrimitive.content,
        )
        assertEquals("string", header["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `all three backends document it identically`() {
        val docs = allBackends.map { it.api().spec().openApiJson() }
        assertEquals(1, docs.distinct().size, "the three documents differ")
        assertTrue(docs.first().contains("X-Request-Id"), "the header is missing from the document")
    }
}
