package example.backends

import dev.pelican.In2
import dev.pelican.In4
import dev.pelican.Outcome
import dev.pelican.UploadedFile
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.openapi.openApiJson
import dev.pelican.test.ApiClient
import dev.pelican.test.apiClient
import dev.pelican.test.shouldBeFailure
import dev.pelican.test.shouldBeResponse
import dev.pelican.test.shouldBuild
import dev.pelican.test.shouldHaveContentType
import dev.pelican.test.shouldHaveHeader
import dev.pelican.test.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        client.request(remember, In2("ada", Note("Hello, ada!"))) shouldBuild "PUT /greetings/ada"
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

    // --------------------------------------------- a failure that carries a header

    /**
     * The 429 `echo` declares carries a payload *and* a `Retry-After`, and both
     * halves have to survive every backend: the body is written by the
     * configured codec as the declared type, and the header goes on that same
     * response.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a declared failure carries its payload and its header`(name: String, client: ApiClient) {
        val res = client.response(echo, In2(null, Note(FLOOD)))

        res shouldHaveStatus 429
        res shouldHaveContentType "application/json"
        res.shouldHaveHeader("Retry-After", "5")
        res.body shouldContain "Slow down"
    }

    /**
     * And read back through the descriptions rather than off the wire: the
     * failure is the one the endpoint declared, and its header comes back as
     * the `Long` the declaration says it is rather than as the string it
     * travelled as.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `and hands the header back typed, on the failure itself`(name: String, client: ApiClient) {
        val refused = client.outcome(echo, In2(null, Note(FLOOD)))

        refused shouldBeFailure tooMuch
        (refused as Outcome.Err)[retryAfter] shouldBe 5L
    }

    /**
     * The reason the header is declared on the failure rather than with
     * `emits(...)`: it belongs to the 429 and to nothing else, so the success
     * the same handler produces cannot carry it.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a header declared on a failure is absent from the success`(name: String, client: ApiClient) {
        val res = client.response(echo, In2(null, Note("hello")))

        res shouldHaveStatus 200
        withClue(res.headers.toString()) { res.header("Retry-After").shouldBeNull() }
    }

    // --------------------------------------------- an endpoint that answers two ways

    /**
     * `remember` declares `200 Greeting` beside `201 Greeting`. Both carry the
     * same payload type, so nothing in the value says which response it is —
     * the handler names one, and every backend has to answer with that status
     * rather than with the endpoint's first.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `each declared success gets its own status`(name: String, client: ApiClient) {
        client.response(remember, In2("ada", Note("Hello again"))) shouldHaveStatus 200
        client.response(remember, In2("zoe", Note("Hello, zoe!"))) shouldHaveStatus 201
    }

    /**
     * And the header declared on the 201 goes on the 201 — the same bargain
     * the 429's `Retry-After` makes, on the success side.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a header declared on one success is absent from the other`(name: String, client: ApiClient) {
        client.response(remember, In2("zoe", Note("hi"))).shouldHaveHeader("Location", "/hello/zoe")

        val known = client.response(remember, In2("ada", Note("hi")))
        withClue(known.headers.toString()) { known.header("Location").shouldBeNull() }
    }

    /**
     * `emits(...)` is still the endpoint's own list, so the filter's
     * `X-Request-Id` reaches both responses while the `Location` reaches one.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the endpoint's own header still reaches every response`(name: String, client: ApiClient) {
        listOf("ada", "zoe").forEach { who ->
            client.response(remember, In2(who, Note("hi"))).header("X-Request-Id").shouldNotBeNull()
        }
    }

    /**
     * Read back through the descriptions rather than off the wire: which
     * response arrived is the declaration the handler named, not the status
     * as a number and not the payload, which is identical either way.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `and which success it was comes back typed`(name: String, client: ApiClient) {
        val made = client.outcome(remember, In2("zoe", Note("Hello, zoe!")))
        (made shouldBeResponse newlyLearned) shouldBe Greeting("Hello, zoe!", "en")
        (made as Outcome.Ok)[greetingAt] shouldBe "/hello/zoe"

        client.outcome(remember, In2("ada", Note("Hello, zoe!"))) shouldBeResponse alreadyKnown
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

    /**
     * The failure's header, in the document, on the response that carries it —
     * published from the same declaration the handler supplied the value
     * through, so the 429 a caller reads about and the 429 it gets are
     * describing one another.
     */

    /**
     * Both 2xx published, each with its own schema and its own headers — which
     * is what OpenAPI's `responses` map was always able to say, and what this
     * library could not until an endpoint could declare two.
     */
    @Test
    fun `both successful responses are published, with the header on the one that carries it`() {
        val responses = Json.parseToJsonElement(pekkoApi().spec().openApiJson())
            .jsonObject["paths"]!!.jsonObject["/greetings/{name}"]!!
            .jsonObject["put"]!!.jsonObject["responses"]!!.jsonObject

        responses.keys shouldBe setOf("201", "200")
        responses["201"]!!.jsonObject["headers"]!!.jsonObject.keys shouldBe setOf("X-Request-Id", "Location")
        responses["200"]!!.jsonObject["headers"]!!.jsonObject.keys shouldBe setOf("X-Request-Id")
        responses["200"]!!.jsonObject["content"]!!.jsonObject.keys shouldBe setOf("application/json")
    }

    @Test
    fun `the failure's header is documented on the failure, not on the success`() {
        val responses = Json.parseToJsonElement(pekkoApi().spec().openApiJson())
            .jsonObject["paths"]!!.jsonObject["/echo"]!!.jsonObject["post"]!!.jsonObject["responses"]!!.jsonObject

        val header = responses["429"]!!.jsonObject["headers"]!!.jsonObject["Retry-After"]!!.jsonObject
        header["required"]!!.jsonPrimitive.content.toBoolean() shouldBe true
        header["schema"]!!.jsonObject["format"]!!.jsonPrimitive.content shouldBe "int64"

        // With a body beside it, which is the pair that used to have no
        // description at all.
        responses["429"]!!.jsonObject["content"]!!.jsonObject.keys shouldBe setOf("application/json")

        withClue("Retry-After leaked onto the success response") {
            responses["200"]!!.jsonObject["headers"]!!.jsonObject.keys shouldBe setOf("X-Request-Id")
        }
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
