package example.backends

import dev.pelican.In2
import dev.pelican.UploadedFile
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.test.ApiClient
import dev.pelican.test.apiClient
import dev.pelican.test.shouldHaveStatus
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream

/**
 * The three input kinds that used to be in the README's "Honest limits", held
 * to one answer across all three interpreters.
 *
 * Same shape as [AllBackendsTest], and for the same reason: a cookie that
 * decoded differently on Ktor, or a form field that Jackson coerced and
 * kotlinx.serialization refused, would be a description two servers disagreed
 * about — which is the one thing this project claims cannot happen.
 */
class CookiesFormsAndUploadsTest {

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

    // ------------------------------------------------------------- cookies

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a cookie parameter reaches the handler decoded`(name: String, client: ApiClient) {
        assertEquals(
            Preferences("fr", "abc123"),
            client.call(preferences, In2("fr", "abc123")),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a declared cookie default applies when the caller sends no cookies at all`(
        name: String,
        client: ApiClient,
    ) {
        // The typed form always supplies both, so the header is dropped from
        // the built request: the default and the optional both live in the
        // description, and each backend has to apply them for itself.
        val res = client.transport.send(
            client.request(preferences, In2("fr", "abc123")).withoutHeader("Cookie"),
        )

        assertEquals(200, res.status)
        assertEquals("""{"locale":"en","session":null}""", res.body)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `one cookie of several is picked out by name, whatever else the jar holds`(
        name: String,
        client: ApiClient,
    ) {
        val res = client.transport.send(
            client.request(preferences, In2("fr", "abc123"))
                .withHeader("Cookie", "consent=all; locale=de; _ga=GA1.2.3; session=xyz"),
        )

        assertEquals("""{"locale":"de","session":"xyz"}""", res.body)
    }

    // ---------------------------------------------------------------- forms

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a form body decodes into the type the schema describes`(name: String, client: ApiClient) {
        assertEquals(
            Session("ada", remember = true, visits = 3),
            client.call(signIn, SignIn("ada", remember = true, visits = 3)),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the form a browser actually posts is read as the declared types`(name: String, client: ApiClient) {
        // What an HTML form sends: every value a string, a checkbox as `on`.
        // Nothing about that says `visits` is a number — the published schema
        // does, which is what both codec modules then agree on.
        val res = client.transport.send(
            client.request(signIn, SignIn("ada", remember = false, visits = 0))
                .withBody("user=ada&remember=on&visits=3"),
        )

        assertEquals(200, res.status)
        assertEquals("""{"user":"ada","remember":true,"visits":3}""", res.body)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a form field that will not decode is a 400 naming the field`(name: String, client: ApiClient) {
        val res = client.transport.send(
            client.request(signIn, SignIn("ada", remember = true, visits = 1))
                .withBody("user=ada&remember=on&visits=lots"),
        ).shouldHaveStatus(400)

        assertTrue("visits" in res.body, res.body)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a form field nobody described is ignored rather than refused`(name: String, client: ApiClient) {
        val res = client.transport.send(
            client.request(signIn, SignIn("ada", remember = true, visits = 1))
                .withBody("user=ada&remember=on&visits=1&_csrf=deadbeef&submit=Sign+in"),
        )

        assertEquals(200, res.status)
        assertEquals("""{"user":"ada","remember":true,"visits":1}""", res.body)
    }

    // ------------------------------------------------------------ multipart

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a multipart upload arrives with its text part decoded and its file readable`(
        name: String,
        client: ApiClient,
    ) {
        val uploaded = client.call(
            uploadFile,
            In2("The notes", file("notes.txt", "text/plain", "line one\nline two")),
        )

        assertEquals(Uploaded("The notes", "notes.txt", "text/plain", "line one\nline two"), uploaded)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a file part larger than the strict body limit is still read, because it is never held whole`(
        name: String,
        client: ApiClient,
    ) {
        // `greetingsApi` sets maxBodyBytes to 4096, and a JSON body over that
        // is a 413. This upload is bigger, and is not — the limit is about
        // what the server holds in memory, and a streamed part is not that.
        val content = "x".repeat(10_000)
        val uploaded = client.call(uploadFile, In2("Big", file("big.txt", "text/plain", content)))

        assertEquals(10_000, uploaded.content.length)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a text part sent after the file part is a 400 that says why`(name: String, client: ApiClient) {
        // Built by hand, because the typed client deliberately cannot produce
        // this: it writes text parts first precisely so a caller does not have
        // to know the rule. A browser with its file input last does the same.
        val boundary = "OutOfOrder"
        val body = listOf(
            "Content-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n" +
                "Content-Type: text/plain\r\n\r\nhello",
            "Content-Disposition: form-data; name=\"caption\"\r\n\r\nToo late",
        ).joinToString("") { "--$boundary\r\n$it\r\n" } + "--$boundary--\r\n"

        val res = client.transport.send(
            client.request(uploadFile, In2("Ignored", file("a.txt", "text/plain", "hello")))
                .withHeader("Content-Type", "multipart/form-data; boundary=$boundary")
                .withBody(body),
        ).shouldHaveStatus(400)

        assertTrue("caption" in res.body, res.body)
        assertTrue("Send 'caption' before it" in res.body, res.body)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a refinement on a text part is enforced, exactly as it is on a query parameter`(
        name: String,
        client: ApiClient,
    ) {
        val res = client.transport.send(
            client.request(uploadFile, In2("", file("a.txt", "text/plain", "hello"))),
        ).shouldHaveStatus(400)

        assertTrue("caption" in res.body, res.body)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a body that is not multipart at all is a 400 rather than a 500`(name: String, client: ApiClient) {
        val res = client.transport.send(
            client.request(uploadFile, In2("x", file("a.txt", "text/plain", "hello")))
                .withHeader("Content-Type", "application/json")
                .withBody("""{"caption":"x"}"""),
        ).shouldHaveStatus(400)

        assertTrue("multipart" in res.body, res.body)
    }

    // ------------------------------------------------------------ and together

    /**
     * As in [AllBackendsTest]: the tests above say each backend matches the
     * description, and this one says they match each other.
     */
    @Test
    fun `all three read a cookie, a form and an upload identically`() {
        fun answers(question: (ApiClient) -> String): Set<String> =
            clients.values.map(question).toSet()

        assertEquals(
            1,
            answers { it.transport.send(it.request(preferences, In2("de", "s1"))).body }.size,
        )
        assertEquals(
            1,
            answers { it.transport.send(it.request(signIn, SignIn("ada", true, 2))).body }.size,
        )
        assertEquals(
            1,
            answers {
                it.transport.send(it.request(uploadFile, In2("c", file("a.txt", "text/plain", "hi")))).body
            }.size,
        )
    }

    private fun file(filename: String, contentType: String, content: String) =
        UploadedFile(filename, contentType, ByteArrayInputStream(content.toByteArray()))
}
