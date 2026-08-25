package example

import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.openapi.DocsOAuth
import io.github.matthewjones372.pelican.openapi.openApiJson
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.docs.Docs
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.github.matthewjones372.pelican.pekko.handledNow
import io.github.matthewjones372.pelican.pekko.handledOrFail
import io.github.matthewjones372.pelican.pekko.start
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class OrdersApiTest {

    companion object {
        private lateinit var server: PelicanServer
        private val client: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        @BeforeAll
        @JvmStatic
        fun boot() {
            server = ordersApi().startWithDocs(
                port = 0,
                systemName = "orders-test",
                docs = ordersDocs,
            )
        }

        @AfterAll
        @JvmStatic
        fun shutdown() {
            server.stop().toCompletableFuture().join()
        }
    }

    private fun url(path: String) = "${server.baseUrl}$path"

    private fun get(path: String, vararg headers: Pair<String, String>): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create(url(path))).GET()
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(
        path: String,
        body: String,
        vararg headers: Pair<String, String>,
    ): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create(url(path)))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    // ------------------------------------------------------------- plain JSON

    @Test
    fun `returns a json object for a known user`() {
        val res = get("/users/1")
        res.statusCode() shouldBe 200
        val obj = Json.parseToJsonElement(res.body()).jsonObject
        obj["id"]!!.jsonPrimitive.int shouldBe 1
        obj["name"]!!.jsonPrimitive.content shouldBe "Ada Lovelace"
        res.headers().firstValue("content-type").get() shouldStartWith "application/json"
    }

    @Test
    fun `notFound from a handler becomes a 404 with a json body`() {
        val res = get("/users/999")
        res.statusCode() shouldBe 404
        val obj = Json.parseToJsonElement(res.body()).jsonObject
        obj["status"]!!.jsonPrimitive.int shouldBe 404
        obj["error"]!!.jsonPrimitive.content shouldBe "No user 999"
    }

    @Test
    fun `an undecodable path parameter is a 400, not a 500`() {
        val res = get("/users/not-a-number")
        res.statusCode() shouldBe 400
        res.body() shouldContain "Invalid parameter"
    }

    @Test
    fun `an unknown path is a 404`() {
        get("/nope").statusCode() shouldBe 404
    }

    // ------------------------------------------------------------- ndjson

    @Test
    fun `streams ndjson with one json document per line`() {
        val res = get("/users/1/orders?limit=7")
        res.statusCode() shouldBe 200
        res.headers().firstValue("content-type").get().substringBefore(';') shouldBe "application/x-ndjson"

        val lines = res.body().trim().lines()
        lines.size shouldBe 7
        lines.forEach { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            o["userId"]!!.jsonPrimitive.int shouldBe 1
            o shouldContainKey "item"
        }
    }

    @Test
    fun `query parameter defaults are applied when absent`() {
        val lines = get("/users/1/orders").body().trim().lines()
        lines.size shouldBe 25 // limit.default(25)
    }

    @Test
    fun `an enum query parameter filters and is case-insensitive`() {
        val lines = get("/users/1/orders?limit=10&status=shipped").body().trim().lines()
        lines.size shouldBe 10
        lines.forEach {
            Json.parseToJsonElement(it).jsonObject["status"]!!.jsonPrimitive.content shouldBe "SHIPPED"
        }
    }

    @Test
    fun `a bad enum value is rejected with a helpful 400`() {
        val res = get("/users/1/orders?status=BANANA")
        res.statusCode() shouldBe 400
        res.body() shouldContain "PENDING"
    }

    @Test
    fun `optional headers are simply null when absent`() {
        get("/users/1/orders?limit=1").statusCode() shouldBe 200
        get("/users/1/orders?limit=1", "X-Trace-Id" to "abc").statusCode() shouldBe 200
    }

    // ------------------------------------------------------------- bodies

    @Test
    fun `posts a json body and gets the declared 201 status`() {
        val res = post(
            "/users/1/orders",
            """{"item":"anvil","quantity":3}""",
            "X-Api-Key" to "let-me-in",
        )
        res.statusCode() shouldBe 201
        val o = Json.parseToJsonElement(res.body()).jsonObject
        o["item"]!!.jsonPrimitive.content shouldBe "anvil"
        o["quantity"]!!.jsonPrimitive.int shouldBe 3
        o["status"]!!.jsonPrimitive.content shouldBe "PENDING"
    }

    @Test
    fun `a missing required header is a 400 before the handler runs`() {
        val res = post("/users/1/orders", """{"item":"anvil"}""")
        res.statusCode() shouldBe 400
        res.body() shouldContain "X-Api-Key"
    }

    @Test
    fun `a handler can reject with 401`() {
        val res = post("/users/1/orders", """{"item":"anvil"}""", "X-Api-Key" to "wrong")
        res.statusCode() shouldBe 401
    }

    @Test
    fun `malformed json is a 400`() {
        val res = post("/users/1/orders", """{"item":}""", "X-Api-Key" to "let-me-in")
        res.statusCode() shouldBe 400
        res.body() shouldContain "Malformed request body"
    }

    @Test
    fun `defaulted body fields work`() {
        val res = post("/users/1/orders", """{"item":"rope"}""", "X-Api-Key" to "let-me-in")
        res.statusCode() shouldBe 201
        Json.parseToJsonElement(res.body()).jsonObject["quantity"]!!.jsonPrimitive.int shouldBe 1
    }

    @Test
    fun `an empty output produces 204 with no body`() {
        val req = HttpRequest.newBuilder(URI.create(url("/users/1/orders/5")))
            .DELETE()
            .header("X-Api-Key", "let-me-in")
            .build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofString())
        res.statusCode() shouldBe 204
        res.body() shouldBe ""
    }

    @Test
    fun `a request byte stream can be piped straight to the response`() {
        val payload = "x".repeat(200_000)
        val res = post("/echo", payload)
        res.statusCode() shouldBe 200
        res.body().length shouldBe payload.length
        res.body() shouldBe payload
    }

    // ------------------------------------------------------------- json array

    @Test
    fun `a json array output is one well-formed array`() {
        val res = get("/users/1/orders/list?limit=3")
        res.statusCode() shouldBe 200
        res.headers().firstValue("content-type").get().substringBefore(';') shouldBe "application/json"

        val body = res.body()
        body shouldStartWith "["
        body shouldEndWith "]"

        val items = Json.parseToJsonElement(body).jsonArray
        items.size shouldBe 3
        items.forEach { it.jsonObject["userId"]!!.jsonPrimitive.int shouldBe 1 }
    }

    @Test
    fun `a json array is framed as it is produced, not assembled and then sent`() {
        // Same reasoning as the SSE timing test below: the source is throttled,
        // so a buffered response would deliver the opening element at the same
        // moment as the closing bracket.
        val req = HttpRequest.newBuilder(URI.create(url("/users/1/orders/list?limit=8"))).GET().build()
        val start = System.nanoTime()
        val res = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        res.statusCode() shouldBe 200

        var firstElementAt = -1L
        val body = StringBuilder()
        res.body().reader().use { r ->
            while (true) {
                val c = r.read()
                if (c < 0) break
                body.append(c.toChar())
                // The end of the first element, i.e. the first thing a consumer
                // could actually parse and act on.
                if (c.toChar() == '}' && firstElementAt < 0) firstElementAt = System.nanoTime()
            }
        }
        val totalMs = (System.nanoTime() - start) / 1_000_000
        val firstMs = (firstElementAt - start) / 1_000_000

        Json.parseToJsonElement(body.toString()).jsonArray.size shouldBe 8
        withClue("stream finished suspiciously fast: ${totalMs}ms") { totalMs shouldBeGreaterThanOrEqualTo 250 }
        withClue("first element at ${firstMs}ms of ${totalMs}ms — looks buffered, not streamed") {
            firstMs shouldBeLessThan totalMs / 2
        }
    }

    @Test
    fun `a json array output documents as an array of the element schema`() {
        val doc = Json.parseToJsonElement(ordersSpec().openApiJson()).jsonObject
        val schema = doc["paths"]!!.jsonObject["/users/{userId}/orders/list"]!!
            .jsonObject["get"]!!.jsonObject["responses"]!!.jsonObject["200"]!!
            .jsonObject["content"]!!.jsonObject["application/json"]!!
            .jsonObject["schema"]!!.jsonObject

        schema["type"]!!.jsonPrimitive.content shouldBe "array"
        schema["items"]!!.jsonObject["\$ref"]!!.jsonPrimitive.content shouldBe "#/components/schemas/Order"
    }

    // ------------------------------------------------------------- sse + backpressure

    @Test
    fun `server-sent events use the sse framing and event name`() {
        val res = get("/users/1/orders/watch?limit=3")
        res.statusCode() shouldBe 200
        withClue(res.headers().firstValue("content-type").toString()) {
            res.headers().firstValue("content-type").get() shouldStartWith "text/event-stream"
        }
        val frames = res.body().split("\n\n").filter { it.isNotBlank() }
        frames.size shouldBe 3
        frames.forEach { frame ->
            frame shouldContain "event: order"
            val data = frame.lines().first { it.startsWith("data: ") }.removePrefix("data: ")
            Json.parseToJsonElement(data).jsonObject shouldContainKey "seq"
        }
    }

    @Test
    fun `elements are delivered as produced, not buffered until the end`() {
        // The source is throttled to one element per 100ms, so if the response
        // were collected before being sent, the first byte would arrive at the
        // same time as the last one.
        val req = HttpRequest.newBuilder(URI.create(url("/users/1/orders/watch?limit=8"))).GET().build()
        val start = System.nanoTime()
        val res = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        res.statusCode() shouldBe 200

        var firstFrameAt = -1L
        var frames = 0
        res.body().bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                if (line.startsWith("data: ")) {
                    if (firstFrameAt < 0) firstFrameAt = System.nanoTime()
                    frames++
                }
            }
        }
        val totalMs = (System.nanoTime() - start) / 1_000_000
        val firstMs = (firstFrameAt - start) / 1_000_000

        frames shouldBe 8
        withClue("stream finished suspiciously fast: ${totalMs}ms") { totalMs shouldBeGreaterThanOrEqualTo 600 }
        withClue("first frame at ${firstMs}ms of ${totalMs}ms — looks buffered, not streamed") {
            firstMs shouldBeLessThan totalMs / 2
        }
    }

    // ------------------------------------------------------------- openapi

    @Test
    fun `serves a coherent openapi document`() {
        val res = get("/openapi.json")
        res.statusCode() shouldBe 200
        val doc = Json.parseToJsonElement(res.body()).jsonObject

        doc["openapi"]!!.jsonPrimitive.content shouldBe "3.1.0"
        doc["info"]!!.jsonObject["title"]!!.jsonPrimitive.content shouldBe "Orders"

        val paths = doc["paths"]!!.jsonObject
        withClue(paths.keys.toString()) { paths shouldContainKey "/users/{userId}" }
        withClue(paths.keys.toString()) { paths shouldContainKey "/users/{userId}/orders" }
        withClue(paths.keys.toString()) { paths shouldContainKey "/users/{userId}/orders/{orderId}" }

        // GET and POST on the same template are merged into one path item.
        val ordersItem = paths["/users/{userId}/orders"]!!.jsonObject
        ordersItem shouldContainKey "get"
        ordersItem shouldContainKey "post"

        // The streaming endpoint advertises its real media type.
        val streamOk = ordersItem["get"]!!.jsonObject["responses"]!!.jsonObject["200"]!!.jsonObject
        streamOk["content"]!!.jsonObject shouldContainKey "application/x-ndjson"

        // Parameters carry their location, requiredness and schema.
        val params = ordersItem["get"]!!.jsonObject["parameters"]!!.jsonArray.map { it.jsonObject }
        val limitParam = params.first { it["name"]!!.jsonPrimitive.content == "limit" }
        limitParam["in"]!!.jsonPrimitive.content shouldBe "query"
        limitParam["required"]!!.jsonPrimitive.boolean shouldBe false
        limitParam["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "integer"

        val userIdParam = params.first { it["name"]!!.jsonPrimitive.content == "userId" }
        userIdParam["in"]!!.jsonPrimitive.content shouldBe "path"
        userIdParam["required"]!!.jsonPrimitive.boolean shouldBe true
        userIdParam["schema"]!!.jsonObject["format"]!!.jsonPrimitive.content shouldBe "int64"

        val statusParam = params.first { it["name"]!!.jsonPrimitive.content == "status" }
        val enumValues = statusParam["schema"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        enumValues shouldBe listOf("PENDING", "SHIPPED", "DELIVERED", "CANCELLED")

        // Model types are hoisted into components and referenced.
        val schemas = doc["components"]!!.jsonObject["schemas"]!!.jsonObject
        withClue(schemas.keys.toString()) { schemas shouldContainKey "Order" }
        withClue(schemas.keys.toString()) { schemas shouldContainKey "User" }
        withClue(schemas.keys.toString()) { schemas shouldContainKey "CreateOrder" }

        val orderSchema = schemas["Order"]!!.jsonObject
        val required = orderSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        required shouldContain "id"
        // quantity has a default in CreateOrder, so it must be optional there
        val createRequired = schemas["CreateOrder"]!!.jsonObject["required"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        createRequired shouldBe listOf("item")

        val streamRef = streamOk["content"]!!.jsonObject["application/x-ndjson"]!!
            .jsonObject["schema"]!!.jsonObject["\$ref"]!!.jsonPrimitive.content
        streamRef shouldBe "#/components/schemas/Order"
    }

    @Test
    fun `declared error responses are documented`() {
        val doc = Json.parseToJsonElement(ordersSpec().openApiJson()).jsonObject
        val getUserOp = doc["paths"]!!.jsonObject["/users/{userId}"]!!.jsonObject["get"]!!.jsonObject
        val responses = getUserOp["responses"]!!.jsonObject
        responses shouldContainKey "200"
        responses shouldContainKey "404"
        responses["404"]!!.jsonObject["description"]!!.jsonPrimitive.content shouldBe "No user with that id"
    }

    @Test
    fun `serves a swagger ui page at the configured path`() {
        val res = get("/api-docs")
        res.statusCode() shouldBe 200
        withClue(res.headers().firstValue("content-type").toString()) {
            res.headers().firstValue("content-type").get() shouldStartWith "text/html"
        }

        val body = res.body()
        withClue(body.take(200)) { body shouldContain "swagger-ui" }
        // Pointed at the served document rather than a copy of it.
        body shouldContain "url: '/openapi.json'"
        body shouldContain "Orders — API reference"

        // The default path is gone, not merely duplicated.
        get("/docs").statusCode() shouldBe 404
    }

    // ------------------------------------------------------------- hidden

    @Test
    fun `a hidden endpoint is served but not documented`() {
        val res = post("/internal/reindex", "", "X-Api-Key" to "let-me-in")
        res.statusCode() shouldBe 202

        // Hidden hides the description, not the door: the credential still counts.
        post("/internal/reindex", "", "X-Api-Key" to "nope").statusCode() shouldBe 401

        val doc = Json.parseToJsonElement(get("/openapi.json").body()).jsonObject
        val paths = doc["paths"]!!.jsonObject
        withClue(paths.keys.toString()) { paths shouldNotContainKey "/internal/reindex" }
    }
}

class SwaggerUiOAuthTest {

    private val oauth = oauth2AuthorizationCode(
        authorizationUrl = "https://id.example.com/authorize",
        tokenUrl = "https://id.example.com/token",
        scopes = mapOf("orders:read" to "Read orders"),
    )

    private val secured = endpoint {
        get("secure")
        security(oauth, "orders:read")
        json<User>()
    }

    private fun serve(docsOAuth: DocsOAuth?, block: (String) -> Unit) {
        val server = api(
            endpoints = listOf(secured handledNow { User(1, "Ada Lovelace", "ada@example.com") }),
            codecs = JacksonCodecs,
        ) {
            title = "Orders"
        }.startWithDocs(
            port = 0,
            systemName = "swagger-oauth-test",
            docs = Docs(docsPath = "/api-docs", oauth = docsOAuth),
        )
        try {
            block(server.baseUrl)
        } finally {
            server.stop().toCompletableFuture().join()
        }
    }

    private fun fetch(url: String): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `the page authorizes as the configured client and serves its own redirect target`() {
        serve(DocsOAuth(clientId = "docs-ui", scopes = listOf("orders:read"))) { base ->
            val page = fetch("$base/api-docs").body()
            page shouldContain "initOAuth"
            page shouldContain """"clientId":"docs-ui""""
            page shouldContain """"usePkceWithAuthorizationCodeGrant":true"""
            // Resolved against the origin the reader is on, not a configured host.
            withClue(page) {
                page shouldContain """oauth2RedirectUrl: window.location.origin + "/api-docs/oauth2-redirect.html""""
            }

            val redirect = fetch("$base/api-docs/oauth2-redirect.html")
            redirect.statusCode() shouldBe 200
            redirect.headers().firstValue("content-type").get() shouldStartWith "text/html"
            redirect.body() shouldContain "swaggerUIRedirectOauth2"

            // The requirement itself reaches the document, padlock and all.
            val doc = Json.parseToJsonElement(fetch("$base/openapi.json").body()).jsonObject
            val schemes = doc["components"]!!.jsonObject["securitySchemes"]!!.jsonObject
            withClue(schemes.keys.toString()) { schemes shouldContainKey "oauth2" }
            schemes["oauth2"]!!.jsonObject["flows"]!!.jsonObject shouldContainKey "authorizationCode"
        }
    }

    @Test
    fun `without docsOAuth there is no redirect page and no initOAuth`() {
        serve(null) { base ->
            val page = fetch("$base/api-docs").body()
            page shouldNotContain "initOAuth"
            fetch("$base/api-docs/oauth2-redirect.html").statusCode() shouldBe 404
        }
    }
}

class SwaggerUiTest {

    private fun serve(openApi: String?, docs: String?, block: (String) -> Unit) {
        val server = api(
            endpoints = listOf(
                getUser handledOrFail { id ->
                    Store.user(id)?.let { ok(it) } ?: noSuchUser(ApiError(404, "No user $id"))
                },
            ),
            codecs = JacksonCodecs,
        ) {
            title = "Orders"
        }.startWithDocs(
            port = 0,
            systemName = "swagger-ui-test",
            docs = Docs(openApiPath = openApi, docsPath = docs),
        )
        try {
            block(server.baseUrl)
        } finally {
            server.stop().toCompletableFuture().join()
        }
    }

    private fun fetch(url: String): HttpResponse<String> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `with a spec endpoint, the page fetches it by url`() {
        serve(openApi = "/openapi.json", docs = "/api-docs") { base ->
            val body = fetch("$base/api-docs").body()
            body shouldContain "url: '/openapi.json'"
            withClue("the document should not also be inlined") { body shouldNotContain "spec: {" }
            fetch("$base/openapi.json").statusCode() shouldBe 200
        }
    }

    @Test
    fun `without a spec endpoint, the document is embedded in the page`() {
        serve(openApi = null, docs = "/api-docs") { base ->
            fetch("$base/openapi.json").statusCode() shouldBe 404

            val body = fetch("$base/api-docs").body()
            withClue("nothing left to fetch, so nothing should be fetched") { body shouldNotContain "url: '" }
            withClue(body.take(400)) { body shouldContain "spec: {" }

            // Embedded whole, not truncated to something Swagger UI cannot read.
            val spec = body.substringAfter("spec: ").substringBefore(", dom_id")
            val doc = Json.parseToJsonElement(spec).jsonObject
            doc["openapi"]!!.jsonPrimitive.content shouldBe "3.1.0"
            doc["paths"]!!.jsonObject shouldContainKey "/users/{userId}"
        }
    }

    @Test
    fun `a description containing a closing script tag cannot break out of the page`() {
        val hazard = endpoint {
            get("hazard")
            summary = "Closes the tag: </script><script>alert(1)</script>"
            json<User>()
        }
        val server = api(
            endpoints = listOf(hazard handledNow { User(1, "a", "b") }),
            codecs = JacksonCodecs,
        ) {
            title = "Hazard"
        }.startWithDocs(
            port = 0,
            systemName = "swagger-escape-test",
            docs = Docs(openApiPath = null, docsPath = "/api-docs"),
        )
        try {
            val body = fetch("${server.baseUrl}/api-docs").body()
            // The literal sequence must not survive into the page.
            body shouldNotContain "</script><script>alert"
            withClue(body.substringAfter("spec: ").take(400)) { body shouldContain "<\\/script>" }
        } finally {
            server.stop().toCompletableFuture().join()
        }
    }
}

class RouteSpecificityTest {

    @Test
    fun `a literal segment beats a capture regardless of declaration order`() {
        val server = ordersApi().start(port = 0, systemName = "specificity-test")
        try {
            val client = HttpClient.newHttpClient()

            // /users/1/orders/watch and /users/1/orders/{orderId} are both
            // four segments. The literal must win.
            val sse = client.send(
                HttpRequest.newBuilder(URI.create("${server.baseUrl}/users/1/orders/watch?limit=1")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            sse.statusCode() shouldBe 200
            sse.headers().firstValue("content-type").get() shouldStartWith "text/event-stream"

            // The capture still works for anything else.
            val del = client.send(
                HttpRequest.newBuilder(URI.create("${server.baseUrl}/users/1/orders/42"))
                    .DELETE().header("X-Api-Key", "let-me-in").build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            del.statusCode() shouldBe 204
        } finally {
            server.stop().toCompletableFuture().join()
        }
    }
}

class TypedInputsTest {

    @Test
    fun `the lens-style endpoint still works alongside the typed ones`() {
        val server = ordersApi().start(port = 0, systemName = "lens-test")
        try {
            val res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${server.baseUrl}/search?limit=3&status=SHIPPED")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            res.statusCode() shouldBe 200
            res.body().trim().lines().size shouldBe 3
        } finally {
            server.stop().toCompletableFuture().join()
        }
    }

    @Test
    fun `declaring a path parameter that is not in the path fails at construction`() {
        val stray = pathParam<Long>("stray")
        val e = shouldThrow<IllegalStateException> {
            endpoint(stray) {
                get("things")
                json<User>()
            }
        }
        e.message shouldContain "declares path parameter 'stray'"
    }

    @Test
    fun `capturing a path parameter nobody declared fails at construction`() {
        val ignored = pathParam<Long>("ignored")
        val other = queryParam<Int>("other")
        val e = shouldThrow<IllegalStateException> {
            endpoint(other) {
                get("things" / ignored)
                json<User>()
            }
        }
        e.message shouldContain "never declares it as an input"
    }

    @Test
    fun `a duplicated path parameter name fails at construction`() {
        val a = pathParam<Long>("dup")
        val b = pathParam<Long>("dup")
        val e = shouldThrow<IllegalStateException> {
            endpoint(a, b) {
                get("things" / a / b)
                json<User>()
            }
        }
        e.message shouldContain "more than once"
    }

    @Test
    fun `typed inputs register themselves for decoding and documentation`() {
        // streamOrders never calls query() or header() — endpoint(...) did it.
        streamOrders.queries.map { it.name } shouldBe listOf("limit", "status")
        streamOrders.headerParams.map { it.name } shouldBe listOf("X-Trace-Id")
        streamOrders.pathSpec.captures.map { it.name } shouldBe listOf("userId")
    }
}

class WrapperCodecTest {

    @JvmInline
    value class AccountId(val value: Long)

    private val accountId = pathParam("accountId", LongCodec.map(::AccountId, AccountId::value))

    private val getAccount = endpoint(accountId) {
        get("accounts" / accountId)
        json<User>()
    }

    @Test
    fun `a wrapper codec decodes to the wrapper type and documents as the underlying one`() {
        val api = api(
            // The handler receives AccountId, not Long. Swapping it for another
            // Long-backed id would not compile.
            endpoints = listOf(
                getAccount handledNow { id: AccountId -> User(id.value, "acct-${id.value}", "a@b.c") },
            ),
            codecs = JacksonCodecs,
        ) {
            title = "Accounts"
        }
        val server = api.start(port = 0, systemName = "wrapper-test")
        try {
            val res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${server.baseUrl}/accounts/77")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            res.statusCode() shouldBe 200
            Json.parseToJsonElement(res.body()).jsonObject["id"]!!.jsonPrimitive.int shouldBe 77
        } finally {
            server.stop().toCompletableFuture().join()
        }

        val schema = Json.parseToJsonElement(
            ApiSpec(listOf(getAccount), JacksonCodecs).openApiJson(),
        ).jsonObject["paths"]!!.jsonObject["/accounts/{accountId}"]!!.jsonObject["get"]!!
            .jsonObject["parameters"]!!.jsonArray.first().jsonObject["schema"]!!.jsonObject

        schema["type"]!!.jsonPrimitive.content shouldBe "integer"
        schema["format"]!!.jsonPrimitive.content shouldBe "int64"
    }
}
