package example.backends

import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.UploadedFile
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.decodeBody
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream

/**
 * Cookies, forms and uploads, held to one answer across all three interpreters.
 *
 * Same shape as [AllBackendsTest]: a cookie decoded differently on Ktor, or a
 * form field Jackson coerced and kotlinx.serialization refused, is a
 * description two servers disagree about.
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
        client.call(preferences, In2("fr", "abc123")) shouldBe Preferences("fr", "abc123")
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

        res.status shouldBe 200
        // The session is absent rather than written as the word: a null
        // property is left out by all three codec modules, so the value read
        // back is what says which null it is.
        res.body shouldBe """{"locale":"en"}"""
        client.decodeBody<Preferences>(res) shouldBe Preferences("en", null)
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

        res.body shouldBe """{"locale":"de","session":"xyz"}"""
    }

    // ---------------------------------------------------------------- forms

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a form body decodes into the type the schema describes`(name: String, client: ApiClient) {
        client.call(signIn, SignIn("ada", remember = true, visits = 3)) shouldBe
            Session("ada", remember = true, visits = 3)
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

        res.status shouldBe 200
        res.body shouldBe """{"user":"ada","remember":true,"visits":3}"""
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a form field that will not decode is a 400 naming the field`(name: String, client: ApiClient) {
        val res = client.transport.send(
            client.request(signIn, SignIn("ada", remember = true, visits = 1))
                .withBody("user=ada&remember=on&visits=lots"),
        ).shouldHaveStatus(400)

        res.body shouldContain "visits"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a form field nobody described is ignored rather than refused`(name: String, client: ApiClient) {
        val res = client.transport.send(
            client.request(signIn, SignIn("ada", remember = true, visits = 1))
                .withBody("user=ada&remember=on&visits=1&_csrf=deadbeef&submit=Sign+in"),
        )

        res.status shouldBe 200
        res.body shouldBe """{"user":"ada","remember":true,"visits":1}"""
    }

    // ----------------------------------------------- the same body, two ways

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the same payload is read from JSON as well as from the form`(name: String, client: ApiClient) {
        // `credentials` is `formBody<SignIn>() or jsonBody<SignIn>()`: one
        // payload, two encodings. The typed call is identical either way —
        // what changes is the Content-Type the client puts on it.
        val posted = SignIn("ada", remember = true, visits = 3)

        client.call(signIn, posted) shouldBe Session("ada", remember = true, visits = 3)
        client.sending("application/json").call(signIn, posted) shouldBe
            Session("ada", remember = true, visits = 3)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `each declared encoding really is sent as itself`(name: String, client: ApiClient) {
        // Not just that both work: that the two requests differ on the wire in
        // the one way they are supposed to.
        val posted = SignIn("ada", remember = true, visits = 3)

        client.request(signIn, posted).body shouldBe "user=ada&remember=true&visits=3"
        client.sending("application/json").request(signIn, posted).body shouldBe
            """{"user":"ada","remember":true,"visits":3}"""
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a media type the endpoint never declared is a 415 naming the ones it did`(
        name: String,
        client: ApiClient,
    ) {
        val res = client.transport.send(
            client.request(signIn, SignIn("ada", remember = true, visits = 3))
                .withHeader("Content-Type", "application/xml")
                .withBody("<signIn/>"),
        ).shouldHaveStatus(415)

        res.body shouldContain "application/json"
        res.body shouldContain "application/x-www-form-urlencoded"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a body with one encoding is still read whatever the Content-Type says`(
        name: String,
        client: ApiClient,
    ) {
        // `echo` takes a plain `jsonBody<Note>()`. With nothing to choose
        // between, the header carries no information the server needs, and a
        // 415 here would refuse callers that have always been served.
        val res = client.transport.send(
            client.request(echo, In2("t-1", Note("hi"))).withHeader("Content-Type", "text/plain"),
        )

        res.status shouldBe 200
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
            In3(
                "The notes",
                file("about.txt", "text/plain", "a poem"),
                file("notes.txt", "text/plain", "line one\nline two"),
            ),
        )

        uploaded shouldBe Uploaded("The notes", "notes.txt", "text/plain", "line one\nline two", "a poem")
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
        val uploaded = client.call(
            uploadFile,
            In3("Big", file("about.txt", "text/plain", "small"), file("big.txt", "text/plain", content)),
        )

        uploaded.content.length shouldBe 10_000
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a buffered part over its declared bound is a 413 naming the part`(name: String, client: ApiClient) {
        val res = client.transport.send(
            client.request(
                uploadFile,
                In3(
                    "Too much",
                    file("about.txt", "text/plain", "x".repeat(600)),
                    file("a.txt", "text/plain", "hi"),
                ),
            ),
        ).shouldHaveStatus(413)

        res.body shouldContain "notes"
        res.body shouldContain "512"
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
            client.request(uploadFile, In3("Ignored", small(), file("a.txt", "text/plain", "hello")))
                .withHeader("Content-Type", "multipart/form-data; boundary=$boundary")
                .withBody(body),
        ).shouldHaveStatus(400)

        res.body shouldContain "caption"
        res.body shouldContain "Send 'caption' before it"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a refinement on a text part is enforced, exactly as it is on a query parameter`(
        name: String,
        client: ApiClient,
    ) {
        val res = client.transport.send(
            client.request(uploadFile, In3("", small(), file("a.txt", "text/plain", "hello"))),
        ).shouldHaveStatus(400)

        res.body shouldContain "caption"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a body that is not multipart at all is a 400 rather than a 500`(name: String, client: ApiClient) {
        val res = client.transport.send(
            client.request(uploadFile, In3("x", small(), file("a.txt", "text/plain", "hello")))
                .withHeader("Content-Type", "application/json")
                .withBody("""{"caption":"x"}"""),
        ).shouldHaveStatus(400)

        res.body shouldContain "multipart"
    }

    // ------------------------------------------------------------ and together

    @Test
    fun `all three read a cookie, a form, a JSON body and a two-file upload identically`() {
        fun answers(question: (ApiClient) -> String): Set<String> =
            clients.values.map(question).toSet()

        answers { it.transport.send(it.request(preferences, In2("de", "s1"))).body }.size shouldBe 1
        answers { it.transport.send(it.request(signIn, SignIn("ada", true, 2))).body }.size shouldBe 1
        answers {
            val json = it.sending("application/json")
            json.transport.send(json.request(signIn, SignIn("ada", true, 2))).body
        }.size shouldBe 1
        answers {
            it.transport.send(it.request(uploadFile, In3("c", small(), file("a.txt", "text/plain", "hi")))).body
        }.size shouldBe 1
    }

    private fun file(filename: String, contentType: String, content: String) =
        UploadedFile(filename, contentType, ByteArrayInputStream(content.toByteArray()))

    /** The buffered part, where what it carries is not what the test is about. */
    private fun small() = file("about.txt", "text/plain", "a note")
}
