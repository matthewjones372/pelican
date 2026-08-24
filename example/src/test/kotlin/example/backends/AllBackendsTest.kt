package example.backends

import dev.pelican.In2
import dev.pelican.In4
import dev.pelican.UploadedFile
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.openapi.openApiJson
import dev.pelican.test.ApiClient
import dev.pelican.test.apiClient
import dev.pelican.test.shouldBuild
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
 * One suite, three interpreters.
 *
 * Every test below is written once and run against Pekko, http4k and Ktor,
 * because the thing under test is the *description* — and a description that
 * two backends honour differently is a description one of them is getting
 * wrong. The parameter is a [Backend], so adding a fourth is one line in
 * `allBackends` and no new assertions.
 *
 * The questions are built from the endpoint values themselves: [ApiClient]
 * turns `call(greet, In2("ada", false))` into a request by reading the same
 * path template, parameter names and payload types the interpreters read. This
 * suite therefore cannot ask two backends different things by accident, and a
 * green run is evidence about the documented contract rather than about three
 * hand-written URLs that happen to agree.
 */
class AllBackendsTest {

    companion object {
        /**
         * Every backend, started once on an OS-chosen port and shared by every
         * invocation below. Three servers is three actor systems / engines, so
         * starting them per test would dominate the run for no extra evidence.
         */
        private val running: Map<String, Running> =
            allBackends.associate { it.name to it.start(port = 0) }

        private val clients: Map<String, ApiClient> =
            running.mapValues { (_, server) -> apiClient(server.baseUrl, JacksonCodecs) }

        /** The parameter list: a name to label the invocation, and a client to drive. */
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

    // ------------------------------------------------------------- a simple GET

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a typed GET returns the value the description promises`(name: String, client: ApiClient) {
        // `greet` is an Endpoint<In2<String, Boolean>, Greeting>, so this call
        // returns a Greeting and passing anything else does not compile.
        val greeting = client.call(greet, In2("ada", false))

        greeting shouldBe Greeting("Hello, ada!", "en")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a query parameter reaches the handler decoded`(name: String, client: ApiClient) {
        client.call(greet, In2("ada", true)).greeting shouldBe "HELLO, ADA!"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the declared default applies when the parameter is left off`(name: String, client: ApiClient) {
        // The typed form always supplies `shout`, so drop it from the built
        // request: the default lives in the description, and each backend has
        // to apply it for itself.
        val res = client.transport.send(client.request(greet, In2("ada", true)).withoutQuery("shout"))

        res.status shouldBe 200
        res.body shouldBe """{"greeting":"Hello, ada!","language":"en"}"""
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a path parameter that does not decode is a 400`(name: String, client: ApiClient) {
        val res = client.transport.send(
            client.request(countdown, 3).withPath("/countdown/not-a-number"),
        )

        res.status shouldBe 400
        res.body shouldContain "Invalid parameter"
    }

    // ------------------------------------------------- more than one value

    /** Every encoding at once, filled in. */
    private fun everything(): In4<List<String>?, List<Long>?, List<String>?, List<String>?> = In4(
        listOf("kotlin", "http"),
        listOf(1L, 2L),
        listOf("beta", "dark"),
        listOf("x", "y"),
    )

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a list reaches the handler as a list, whichever way it was spread`(name: String, client: ApiClient) {
        // Repeated query, comma-joined query, comma-joined header and a cookie
        // sent as two pairs — four encodings, one handler, no splitting.
        client.call(filters, everything()) shouldBe
            Filters(listOf("kotlin", "http"), listOf(1L, 2L), listOf("beta", "dark"), listOf("x", "y"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the request is spread the way each declaration says`(name: String, client: ApiClient) {
        val request = client.request(filters, everything())

        request.target shouldContain "tag=kotlin&tag=http"
        request.target shouldContain "id=1%2C2"
        request.headers shouldContain ("X-Feature" to "beta,dark")
        request.headers shouldContain ("Cookie" to "seen=x; seen=y")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `an absent list is sent as nothing at all`(name: String, client: ApiClient) {
        // Which is why absent reads back as null rather than as an empty list:
        // there is no request here that could have meant the empty one.
        val request = client.request(filters, In4(null, null, null, null))

        request shouldBuild "GET /filters"
        request.headers.map { it.first } shouldNotContain "X-Feature"
        client.transport.send(request).body shouldBe """{"tags":[],"ids":[],"features":[],"seen":[]}"""
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a refinement on the element rejects one bad element`(name: String, client: ApiClient) {
        val res = client.transport.send(client.request(filters, In4(null, listOf(2L, 0L), null, null)))

        res.status shouldBe 400
        res.body shouldContain "Cannot decode '0' for 'id'"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `an unknown path is a 404`(name: String, client: ApiClient) {
        client.transport.send(client.request(greet, In2("ada", false)).withPath("/nope")).status shouldBe 404
    }

    // ------------------------------------------------------------ the contract

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `every backend answers at the URLs the descriptions publish`(name: String, client: ApiClient) {
        // Every other test here builds its request from the description the
        // backend also routes on, so the two agree by construction: rename
        // `"hello"` to `"greetings"` and all of them stay green while every
        // caller already deployed against `/hello/ada` starts getting a 404.
        //
        // These literals are the only thing in the suite that does not move
        // when a description does. They are the URL a caller was given.
        val greeting = client.request(greet, In2("ada", false))

        greeting shouldBuild "GET /hello/ada?shout=false"
        client.request(countdown, 3) shouldBuild "GET /countdown/3"
        client.request(echo, In2("trace-1", Note("hi"))) shouldBuild "POST /echo"
        client.request(preferences, In2("fr", "abc123")) shouldBuild "GET /preferences"
        client.request(signIn, SignIn("ada", remember = true, visits = 3)) shouldBuild "POST /sign-in"
        val upload = UploadedFile("big.txt", "text/plain", ByteArrayInputStream("hello".toByteArray()))
        client.request(uploadFile, In2("Big", upload)) shouldBuild "POST /upload"

        // The line above pins what the URL is; this one pins that *this*
        // backend serves it. Three interpreters agreeing on how to build a
        // request would be no comfort if one of them routed it elsewhere.
        client.transport.send(greeting).status shouldBe 200
    }

    // ------------------------------------------------------------- the stream

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a streamed response has the same rows and framing`(name: String, client: ApiClient) {
        client.collect(countdown, 3).map { it.seq } shouldBe listOf(3, 2, 1)

        val res = client.response(countdown, 3)
        res.contentType shouldBe "application/x-ndjson"
        res.body.lines().filter { it.isNotBlank() } shouldBe listOf(
            """{"seq":3,"at":"T-minus-3"}""",
            """{"seq":2,"at":"T-minus-2"}""",
            """{"seq":1,"at":"T-minus-1"}""",
        )
    }

    // ------------------------------------------------------------- the document

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the generated document describes the endpoints, whichever backend is bound`(
        name: String,
        client: ApiClient,
    ) {
        val document = allBackends.single { it.name == name }.api().spec().openApiJson()

        withClue(document.take(300)) { document shouldContain "\"operationId\": \"greet\"" }
        withClue(document.take(300)) { document shouldContain "\"/hello/{name}\"" }
    }

    // ------------------------------------------------------------- and together

    /**
     * The tests above assert each backend matches the description. This one
     * asserts they match *each other*, byte for byte — the claim that would
     * still be worth checking even if every description-level assertion above
     * were somehow satisfied three different ways.
     */
    @Test
    fun `all three answer identically, and generate the identical document`() {
        val bodies = clients.mapValues { (_, client) ->
            client.transport.send(client.request(greet, In2("ada", true))).body
        }
        withClue("backends disagreed: $bodies") { bodies.values.toSet().size shouldBe 1 }

        val streams = clients.mapValues { (_, client) -> client.response(countdown, 3).body }
        withClue("streamed bodies disagreed: $streams") { streams.values.toSet().size shouldBe 1 }

        val documents = allBackends.associate { it.name to it.api().spec().openApiJson() }
        withClue("documents disagreed between backends") { documents.values.toSet().size shouldBe 1 }
    }
}
