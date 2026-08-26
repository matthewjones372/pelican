package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import kotlin.reflect.KType

/**
 * The transport a generated client can be handed instead of a socket.
 *
 * Payloads are strings and the codec is the identity, because what is under
 * test is the crossing rather than a JSON library.
 */
class InMemoryClientTransportTest {

    private object Strings : Codecs {
        @Suppress("UNCHECKED_CAST")
        override fun <T> codec(type: KType): BodyCodec<T> = object : BodyCodec<String> {
            override fun encodeToString(value: String) = value
            override fun decodeFromString(text: String) = text
        } as BodyCodec<T>

        /**
         * The same identity, whatever the media type. Overridden because the
         * default refuses everything but JSON, which is what makes a response
         * declared as `text/csv` with nothing to write it a startup failure.
         */
        override fun <T> codec(type: KType, mediaType: String): BodyCodec<T> = codec(type)

        override fun schema(type: KType, components: SchemaComponents): JsonObj =
            jsonObj { "type" to "string" }
    }

    private val id = pathParam<Long>("id")
    private val loud = queryParam<Boolean>("loud").default(false)
    private val trace = headerParam<String>("X-Trace-Id").optional()
    private val theme = cookieParam<String>("theme").optional()
    private val newThing = jsonBody<String>()
    private val upload = rawBody()

    private val missing = errorJson<String>(404, "No thing with that id")

    private val tag = pathParam<String>("tag")

    private val getTag = endpoint(tag) {
        get("tags" / tag)
        operationId = "getTag"
        json<String>()
    }

    private val getThing = endpoint(id, loud, trace, theme) {
        get("things" / id)
        operationId = "getThing"
        json<String>() orFail missing
    }

    private val createThing = endpoint(newThing) {
        post("things")
        operationId = "createThing"
        json<String>(status = 201)
    }

    private val streamThings = endpoint {
        get("ticks")
        operationId = "streamThings"
        ndjson<String>()
    }

    private val watchThings = endpoint {
        get("watch")
        operationId = "watchThings"
        sse<String>(id = { it.substringAfter('-') })
    }

    private val breakThing = endpoint {
        get("boom")
        operationId = "breakThing"
        text()
    }

    private val takeBytes = endpoint(upload) {
        post("bytes")
        operationId = "takeBytes"
        empty(status = 202)
    }

    /** The same value, offered two ways: `Accept` picks, here as on a socket. */
    private val exportTag = endpoint(tag) {
        get("tags" / tag / "export")
        operationId = "exportTag"
        negotiated(json<String>(), media<String>("text/csv"))
    }

    /** How many elements the stream has produced, so laziness can be asserted. */
    private val produced = java.util.concurrent.atomic.AtomicInteger()

    private fun handlers(): List<ServerEndpoint> = listOf(
        ServerEndpoint(getThing) { p ->
            val found =
                if (p[id] == 7L) ok(listOf("thing-7", p[loud].toString(), p[trace], p[theme]).joinToString("/"))
                else missing("no thing ${p[id]}")
            CompletableFuture.completedStage(found as Any?)
        },
        ServerEndpoint(createThing) { p -> CompletableFuture.completedStage("made ${p[newThing]}" as Any?) },
        ServerEndpoint(getTag) { p -> CompletableFuture.completedStage("tag ${p[tag]}" as Any?) },
        ServerEndpoint(streamThings) { _ ->
            val ticks = generateSequence(1) { it + 1 }
                .take(5)
                .map { "tick-$it".also { _ -> produced.incrementAndGet() } }
            CompletableFuture.completedStage(ticks as Any?)
        },
        ServerEndpoint(watchThings) { p ->
            val from = p.lastEventId()?.toInt() ?: 0
            CompletableFuture.completedStage(sequenceOf(1, 2, 3).filter { it > from }.map { "tick-$it" } as Any?)
        },
        ServerEndpoint(breakThing) { _ -> throw IllegalStateException("the database is on fire") },
        ServerEndpoint(takeBytes) { _ -> CompletableFuture.completedStage(Unit as Any?) },
        ServerEndpoint(exportTag) { p -> CompletableFuture.completedStage("tag ${p[tag]}" as Any?) },
    )

    private fun api(
        filters: List<Filter> = emptyList(),
        onServerError: ((String, Endpoint<*, *>?, Throwable) -> Unit)? = null,
    ) = api(handlers(), codecs = Strings) {
        filters.forEach { filter(it) }
        onServerError?.let { onError(it) }
    }

    private fun send(
        transport: ClientTransport,
        method: Method,
        url: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ClientRequest.Body = ClientRequest.Body.Empty,
    ): ClientResponse = transport.send(ClientRequest(method, url, headers, body)).toCompletableFuture().join()

    // ------------------------------------------------------------ the crossing

    @Test
    fun `a request reaches the handler the routes already bind`() {
        val response = send(
            InMemoryClientTransport(api()),
            Method.GET,
            "http://things.test/things/7?loud=true",
            headers = listOf("X-Trace-Id" to "t-1", "Cookie" to "theme=dark"),
        )

        response.status shouldBe 200
        response.header("Content-Type") shouldBe "application/json"
        response.text() shouldBe "thing-7/true/t-1/dark"
    }

    @Test
    fun `the rendering the caller asked for is the one that crosses`() {
        val transport = InMemoryClientTransport(api())

        val asCsv = send(
            transport, Method.GET, "http://things.test/tags/kotlin/export",
            headers = listOf("Accept" to "text/csv"),
        )
        asCsv.header("Content-Type") shouldBe "text/csv"
        asCsv.text() shouldBe "tag kotlin"

        val asJson = send(
            transport, Method.GET, "http://things.test/tags/kotlin/export",
            headers = listOf("Accept" to "application/json"),
        )
        asJson.header("Content-Type") shouldBe "application/json"
    }

    @Test
    fun `and a caller that names none takes the first declared, as a bound server answers it`() {
        val silent = send(InMemoryClientTransport(api()), Method.GET, "http://things.test/tags/kotlin/export")

        silent.header("Content-Type") shouldBe "application/json"
    }

    @Test
    fun `a caller that will take neither is refused here too`() {
        val refused = send(
            InMemoryClientTransport(api()), Method.GET, "http://things.test/tags/kotlin/export",
            headers = listOf("Accept" to "application/xml"),
        )

        refused.status shouldBe 406
    }

    @Test
    fun `an absent optional input is the default the declaration named`() {
        val response = send(InMemoryClientTransport(api()), Method.GET, "http://things.test/things/7")

        response.text() shouldBe "thing-7/false/null/null"
    }

    @Test
    fun `a reconnect crosses as the resume point the handler reads`() {
        val transport = InMemoryClientTransport(api())

        send(transport, Method.GET, "http://things.test/watch").text() shouldBe
            "id: 1\ndata: tick-1\n\nid: 2\ndata: tick-2\n\nid: 3\ndata: tick-3\n\n"

        send(
            transport,
            Method.GET,
            "http://things.test/watch",
            headers = listOf("Last-Event-ID" to "2"),
        ).text() shouldBe "id: 3\ndata: tick-3\n\n"
    }

    @Test
    fun `a body crosses as the value the endpoint declared`() {
        val response = send(
            InMemoryClientTransport(api()),
            Method.POST,
            "http://things.test/things",
            headers = listOf("Content-Type" to "application/json"),
            body = ClientRequest.Body.Text("anvil"),
        )

        response.status shouldBe 201
        response.text() shouldBe "made anvil"
    }

    @Test
    fun `a declared failure comes back as the status it was declared with`() {
        val response = send(InMemoryClientTransport(api()), Method.GET, "http://things.test/things/9")

        response.status shouldBe 404
        response.text() shouldBe "no thing 9"
    }

    // ------------------------------------------------------------ the whole server

    @Test
    fun `the filters the api declares run, as they do on a bound server`() {
        val refuseAnonymous = before { p ->
            if ((p.underlying as ClientRequest).header("Authorization") == null) unauthorized("No token")
        }

        val transport = InMemoryClientTransport(api(filters = listOf(refuseAnonymous)))

        send(transport, Method.GET, "http://things.test/things/7").status shouldBe 401
        send(
            transport,
            Method.GET,
            "http://things.test/things/7",
            headers = listOf("Authorization" to "Bearer t"),
        ).status shouldBe 200
    }

    /**
     * The path reaches the trie as it was written, so the segment decoding is
     * the one the routes use: percent escapes, and a `+` that stays a `+`.
     * Decoding it here first would be a second, quieter answer.
     */
    @Test
    fun `a captured segment decodes as a path, as it does through a route`() {
        val transport = InMemoryClientTransport(api())

        send(transport, Method.GET, "http://things.test/tags/c%2B%2B").text() shouldBe "tag c++"
        send(transport, Method.GET, "http://things.test/tags/a+b").text() shouldBe "tag a+b"
    }

    /**
     * The trie decodes a capture as it matches, so a segment nobody could have
     * written is refused there. It reaches a caller as the 400 each backend
     * gives it rather than as a throw out of the transport.
     */
    @Test
    fun `a segment that is not percent-encoded is the 400 it is on a bound server`() {
        val response = send(InMemoryClientTransport(api()), Method.GET, "http://things.test/things/%zz")

        response.status shouldBe 400
        response.text() shouldContain "Malformed request path"
    }

    @Test
    fun `a path nobody described is a 404, and a method that path does not answer is a 405`() {
        val transport = InMemoryClientTransport(api())

        send(transport, Method.GET, "http://things.test/nowhere").status shouldBe 404
        send(transport, Method.DELETE, "http://things.test/things/7").status shouldBe 405
    }

    @Test
    fun `a failure nobody described is a 500 carrying a reference, and the hook is told`() {
        val seen = mutableListOf<String>()
        val transport = InMemoryClientTransport(
            api(onServerError = { reference, _, error -> seen += "$reference ${error.message}" }),
        )

        val response = send(transport, Method.GET, "http://things.test/boom")
        val rendered = response.text()

        response.status shouldBe 500
        withClue("the message a handler threw must not travel") { rendered shouldContain "Reference:" }
        rendered shouldContain "Internal server error"
        seen.single() shouldContain "the database is on fire"
    }

    // ------------------------------------------------------------ streaming

    @Test
    fun `a stream is pulled as it is read, not produced before the response`() {
        val response = send(InMemoryClientTransport(api()), Method.GET, "http://things.test/ticks")

        response.header("Content-Type") shouldBe "application/x-ndjson"
        withClue("the whole stream ran before a byte was read") { produced.get() shouldBe 0 }

        val first = response.body.bufferedReader().readLine()
        first shouldBe "tick-1"
        withClue("reading one element produced $produced") { (produced.get() < 5) shouldBe true }
    }

    // ------------------------------------------------------------ what cannot cross

    @Test
    fun `a bytes body is refused by name, since the handle belongs to a backend`() {
        val failure = shouldThrow<UnsupportedInMemoryCall> {
            send(
                InMemoryClientTransport(api()),
                Method.POST,
                "http://things.test/bytes",
                body = ClientRequest.Body.Text("some bytes"),
            )
        }

        withClue(failure.message) { failure.message shouldContain "bytes(" }
    }
}
