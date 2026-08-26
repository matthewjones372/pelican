package example.backends

import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.In4
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.UploadedFile
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.openapi.openApiJson
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.apiClient
import io.github.matthewjones372.pelican.test.rawText
import io.github.matthewjones372.pelican.test.shouldBeFailure
import io.github.matthewjones372.pelican.test.shouldBeResponse
import io.github.matthewjones372.pelican.test.shouldBuild
import io.github.matthewjones372.pelican.test.shouldHaveContentType
import io.github.matthewjones372.pelican.test.shouldHaveHeader
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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

    // ------------------------------------------------------ text, and no body
    //
    // Two output kinds that were described once per backend, in two
    // near-identical `TestApi.kt` files, and not at all on Pekko.

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `plain text arrives as text, with the media type that says so`(name: String, client: ApiClient) {
        val res = client.response(motd, Unit)

        withClue(name) {
            res shouldHaveStatus 200
            res.body shouldBe "Be excellent to each other."
            res shouldHaveContentType "text/plain"
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a 204 carries no body at all`(name: String, client: ApiClient) {
        val res = client.response(forget, "ada")

        withClue(name) {
            res shouldHaveStatus 204
            res.body shouldBe ""
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `and still carries the header the endpoint emits`(name: String, client: ApiClient) {
        // The value is minted per request by a filter, so what is asserted is
        // that it is there at all.
        withClue(name) { client.response(forget, "ada").header("X-Request-Id").shouldNotBeNull() }
    }

    // --------------------------------------------- an array, bytes, and a body unread

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a streamed json array is one array whichever backend framed it`(name: String, client: ApiClient) {
        val res = client.response(everyone, Unit)

        withClue(name) {
            res shouldHaveStatus 200
            res shouldHaveContentType "application/json"
            val languages = Json.parseToJsonElement(res.body).jsonArray
                .map { it.jsonObject["language"]!!.jsonPrimitive.content }
            languages shouldBe listOf("en", "fr")
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `opaque bytes keep the media type the description gave them`(name: String, client: ApiClient) {
        val res = client.response(logo, Unit)

        withClue(name) {
            res shouldHaveStatus 200
            res shouldHaveContentType "image/png"
            res.body shouldBe "PELICAN"
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `and the document describes them as bytes of that type`(name: String, client: ApiClient) {
        withClue(name) {
            val json = allBackends.single { it.name == name }.api().spec().openApiJson()
            val doc = Json.parseToJsonElement(json).jsonObject
            val content = doc["paths"]!!.jsonObject["/logo"]!!.jsonObject["get"]!!
                .jsonObject["responses"]!!.jsonObject["200"]!!.jsonObject["content"]!!.jsonObject
            content.keys shouldBe setOf("image/png")
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a raw body is handed back without the framework reading it`(name: String, client: ApiClient) {
        val sent = "not json, not a form, just bytes"
        val res = client.transport.send(client.request(echoRaw, rawText(sent)))

        withClue(name) {
            res shouldHaveStatus 200
            res.body shouldBe sent
        }
    }

    // ------------------------------------------------------------ server-sent events

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `sse frames are core's, so all three write the same ones`(name: String, client: ApiClient) {
        val res = client.response(ticker, Unit)

        withClue(name) {
            res shouldHaveStatus 200
            res shouldHaveContentType "text/event-stream"
            // `event:` then `data:` then a blank line, which is what
            // `SseOutput.frame` writes and what a conformant client parses.
            res.body shouldBe
                "event: tick\ndata: {\"seq\":1,\"at\":\"one\"}\n\n" +
                "event: tick\ndata: {\"seq\":2,\"at\":\"two\"}\n\n"
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
    fun `a list header split over two field lines is still two values`(name: String, client: ApiClient) {
        // RFC 9110 says two lines under one name are one field joined by
        // commas, and a proxy is free to rewrite one spelling into the other.
        // The client sends the joined line, so this is the spelling no test
        // sent: two pairs, which the transport writes as two field lines.
        val joined = client.request(filters, In4(null, null, listOf("beta", "dark"), null))
        val split = RequestSpec(
            joined.method,
            joined.path,
            joined.query,
            joined.headers.filterNot { it.first == "X-Feature" } +
                listOf("X-Feature" to "beta", "X-Feature" to "dark"),
            joined.body,
        )

        withClue(name) {
            client.transport.send(split).body shouldBe
                """{"tags":[],"ids":[],"features":["beta","dark"],"seen":[]}"""
        }
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

    // ------------------------------------------------ what the request line said
    //
    // One decoder, in core, reached the same way by all three: the backends used
    // to hand their router three different spellings of the same path and then
    // decode it three different ways. `roundtrip` hands back what arrived, so
    // every claim below is about the request line and nothing else.

    /** What arrived in the path, for a line built by hand rather than from a value. */
    private fun ApiClient.pathOf(rawPath: String): String =
        Json.parseToJsonElement(transport.send(request(roundtrip, In2("x", null)).withPath(rawPath)).body)
            .jsonObject["fromPath"]!!.jsonPrimitive.content

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a plus in a path segment is a plus, not a space`(name: String, client: ApiClient) {
        withClue(name) { client.call(roundtrip, In2("c++", null)).fromPath shouldBe "c++" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `and an unencoded one means the same thing`(name: String, client: ApiClient) {
        // A legal request line: RFC 3986 gives `+` no meaning in a path, and
        // only a form decoder reads it as a space.
        withClue(name) { client.pathOf("/items/c++") shouldBe "c++" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `an encoded slash stays inside the segment that carried it`(name: String, client: ApiClient) {
        // The split happens before anything is decoded, so this is one segment
        // holding a slash rather than two segments and a different route.
        withClue(name) { client.call(roundtrip, In2("a/b", null)).fromPath shouldBe "a/b" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `an encoded space is a space`(name: String, client: ApiClient) {
        withClue(name) {
            client.call(roundtrip, In2("ada lovelace", null)).fromPath shouldBe "ada lovelace"
            client.pathOf("/items/ada%20lovelace") shouldBe "ada lovelace"
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `an escape spelling an ordinary character decodes to it`(name: String, client: ApiClient) {
        withClue(name) { client.pathOf("/items/%61da") shouldBe "ada" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `and decoding happens once, so an encoded escape arrives as text`(name: String, client: ApiClient) {
        withClue(name) { client.pathOf("/items/%2561") shouldBe "%61" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a trailing slash does not change which endpoint answers`(name: String, client: ApiClient) {
        withClue(name) {
            client.pathOf("/items/x/") shouldBe "x"
            client.transport.send(client.request(motd, Unit).withPath("/motd/")).status shouldBe 200
        }
    }

    // ------------------------------------------------------------ the contract

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `every backend answers at the URLs the descriptions publish`(name: String, client: ApiClient) {
        val greeting = client.request(greet, In2("ada", false))

        greeting shouldBuild "GET /hello/ada?shout=false"
        client.request(countdown, 3) shouldBuild "GET /countdown/3"
        client.request(echo, In2("trace-1", Note("hi"))) shouldBuild "POST /echo"
        client.request(remember, In2("ada", Note("Hello, ada!"))) shouldBuild "PUT /greetings/ada"
        client.request(preferences, In2("fr", "abc123")) shouldBuild "GET /preferences"
        client.request(signIn, SignIn("ada", remember = true, visits = 3)) shouldBuild "POST /sign-in"
        val upload = UploadedFile("big.txt", "text/plain", ByteArrayInputStream("hello".toByteArray()))
        val notes = UploadedFile("about.txt", "text/plain", ByteArrayInputStream("note".toByteArray()))
        client.request(uploadFile, In3("Big", notes, upload)) shouldBuild "POST /upload"

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a declared failure carries its payload and its header`(name: String, client: ApiClient) {
        val res = client.response(echo, In2(null, Note(FLOOD)))

        res shouldHaveStatus 429
        res shouldHaveContentType "application/json"
        res.shouldHaveHeader("Retry-After", "5")
        res.body shouldContain "Slow down"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `and hands the header back typed, on the failure itself`(name: String, client: ApiClient) {
        val refused = client.outcome(echo, In2(null, Note(FLOOD)))

        refused shouldBeFailure tooMuch
        (refused as Outcome.Err)[retryAfter] shouldBe 5L
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a header declared on a failure is absent from the success`(name: String, client: ApiClient) {
        val res = client.response(echo, In2(null, Note("hello")))

        res shouldHaveStatus 200
        withClue(res.headers.toString()) { res.header("Retry-After").shouldBeNull() }
    }

    // --------------------------------------------- an endpoint that answers two ways

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `each declared success gets its own status`(name: String, client: ApiClient) {
        client.response(remember, In2("ada", Note("Hello again"))) shouldHaveStatus 200
        client.response(remember, In2("zoe", Note("Hello, zoe!"))) shouldHaveStatus 201
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a header declared on one success is absent from the other`(name: String, client: ApiClient) {
        client.response(remember, In2("zoe", Note("hi"))).shouldHaveHeader("Location", "/hello/zoe")

        val known = client.response(remember, In2("ada", Note("hi")))
        withClue(known.headers.toString()) { known.header("Location").shouldBeNull() }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `the endpoint's own header still reaches every response`(name: String, client: ApiClient) {
        listOf("ada", "zoe").forEach { who ->
            client.response(remember, In2(who, Note("hi"))).header("X-Request-Id").shouldNotBeNull()
        }
    }

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

        responses["429"]!!.jsonObject["content"]!!.jsonObject.keys shouldBe setOf("application/json")

        withClue("Retry-After leaked onto the success response") {
            responses["200"]!!.jsonObject["headers"]!!.jsonObject.keys shouldBe setOf("X-Request-Id")
        }
    }

    // --------------------------------------------------- served from elsewhere

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `an endpoint served from elsewhere is still routed here`(name: String, client: ApiClient) {
        val file = UploadedFile("big.txt", "text/plain", ByteArrayInputStream("hello".toByteArray()))
        val about = UploadedFile("about.txt", "text/plain", ByteArrayInputStream("note".toByteArray()))
        val request = client.request(uploadFile, In3("Big", about, file))

        // No host on the request: the transport is what decides where it goes.
        request shouldBuild "POST /upload"
        client.transport.send(request).status shouldBe 200
    }

    // ---------------------------------------------------------- and not routed

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a webhook is not routed by any backend`(name: String, client: ApiClient) {
        val send = client.request(echo, In2(null, Note("recorded"))).withPath("/")

        client.transport.send(send).status shouldBe 404
    }

    /** And yet it is published, which is the other half of the same claim. */
    @Test
    fun `and the document declares it, under webhooks rather than under paths`() {
        val document = Json.parseToJsonElement(pekkoApi().spec().openApiJson()).jsonObject

        document["webhooks"]!!.jsonObject.keys shouldBe setOf("greetingRecorded")
        document["paths"]!!.jsonObject.keys shouldNotContain "/"
    }

    @Test
    fun `and the document says where it is served, on the operation`() {
        val operation = Json.parseToJsonElement(pekkoApi().spec().openApiJson())
            .jsonObject["paths"]!!.jsonObject["/upload"]!!.jsonObject["post"]!!.jsonObject

        operation["servers"]!!.jsonArray.map { it.jsonObject["url"]!!.jsonPrimitive.content } shouldBe
            listOf("https://uploads.example.com")
    }

    // ------------------------------------------------------------- and together

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
