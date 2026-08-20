package example

import dev.pelican.*
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.openapi.DocsOAuth
import dev.pelican.openapi.openApiJson
import dev.pelican.pekko.PelicanServer
import dev.pelican.pekko.docs.Docs
import dev.pelican.pekko.docs.startWithDocs
import dev.pelican.pekko.handledNow
import dev.pelican.pekko.handledOrFail
import dev.pelican.pekko.start
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
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
        assertEquals(200, res.statusCode())
        val obj = Json.parseToJsonElement(res.body()).jsonObject
        assertEquals(1, obj["id"]!!.jsonPrimitive.int)
        assertEquals("Ada Lovelace", obj["name"]!!.jsonPrimitive.content)
        assertTrue(res.headers().firstValue("content-type").get().startsWith("application/json"))
    }

    @Test
    fun `notFound from a handler becomes a 404 with a json body`() {
        val res = get("/users/999")
        assertEquals(404, res.statusCode())
        val obj = Json.parseToJsonElement(res.body()).jsonObject
        assertEquals(404, obj["status"]!!.jsonPrimitive.int)
        assertEquals("No user 999", obj["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an undecodable path parameter is a 400, not a 500`() {
        val res = get("/users/not-a-number")
        assertEquals(400, res.statusCode())
        assertTrue(res.body().contains("Invalid parameter"), res.body())
    }

    @Test
    fun `an unknown path is a 404`() {
        assertEquals(404, get("/nope").statusCode())
    }

    // ------------------------------------------------------------- ndjson

    @Test
    fun `streams ndjson with one json document per line`() {
        val res = get("/users/1/orders?limit=7")
        assertEquals(200, res.statusCode())
        assertEquals(
            "application/x-ndjson",
            res.headers().firstValue("content-type").get().substringBefore(';'),
        )

        val lines = res.body().trim().lines()
        assertEquals(7, lines.size)
        lines.forEach { line ->
            val o = Json.parseToJsonElement(line).jsonObject
            assertEquals(1, o["userId"]!!.jsonPrimitive.int)
            assertTrue(o.containsKey("item"))
        }
    }

    @Test
    fun `query parameter defaults are applied when absent`() {
        val lines = get("/users/1/orders").body().trim().lines()
        assertEquals(25, lines.size) // limit.default(25)
    }

    @Test
    fun `an enum query parameter filters and is case-insensitive`() {
        val lines = get("/users/1/orders?limit=10&status=shipped").body().trim().lines()
        assertEquals(10, lines.size)
        lines.forEach {
            assertEquals("SHIPPED", Json.parseToJsonElement(it).jsonObject["status"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a bad enum value is rejected with a helpful 400`() {
        val res = get("/users/1/orders?status=BANANA")
        assertEquals(400, res.statusCode())
        assertTrue(res.body().contains("PENDING"), res.body())
    }

    @Test
    fun `optional headers are simply null when absent`() {
        assertEquals(200, get("/users/1/orders?limit=1").statusCode())
        assertEquals(200, get("/users/1/orders?limit=1", "X-Trace-Id" to "abc").statusCode())
    }

    // ------------------------------------------------------------- bodies

    @Test
    fun `posts a json body and gets the declared 201 status`() {
        val res = post(
            "/users/1/orders",
            """{"item":"anvil","quantity":3}""",
            "X-Api-Key" to "let-me-in",
        )
        assertEquals(201, res.statusCode())
        val o = Json.parseToJsonElement(res.body()).jsonObject
        assertEquals("anvil", o["item"]!!.jsonPrimitive.content)
        assertEquals(3, o["quantity"]!!.jsonPrimitive.int)
        assertEquals("PENDING", o["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a missing required header is a 400 before the handler runs`() {
        val res = post("/users/1/orders", """{"item":"anvil"}""")
        assertEquals(400, res.statusCode())
        assertTrue(res.body().contains("X-Api-Key"), res.body())
    }

    @Test
    fun `a handler can reject with 401`() {
        val res = post("/users/1/orders", """{"item":"anvil"}""", "X-Api-Key" to "wrong")
        assertEquals(401, res.statusCode())
    }

    @Test
    fun `malformed json is a 400`() {
        val res = post("/users/1/orders", """{"item":}""", "X-Api-Key" to "let-me-in")
        assertEquals(400, res.statusCode())
        assertTrue(res.body().contains("Malformed request body"), res.body())
    }

    @Test
    fun `defaulted body fields work`() {
        val res = post("/users/1/orders", """{"item":"rope"}""", "X-Api-Key" to "let-me-in")
        assertEquals(201, res.statusCode())
        assertEquals(1, Json.parseToJsonElement(res.body()).jsonObject["quantity"]!!.jsonPrimitive.int)
    }

    @Test
    fun `an empty output produces 204 with no body`() {
        val req = HttpRequest.newBuilder(URI.create(url("/users/1/orders/5")))
            .DELETE()
            .header("X-Api-Key", "let-me-in")
            .build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(204, res.statusCode())
        assertEquals("", res.body())
    }

    @Test
    fun `a request byte stream can be piped straight to the response`() {
        val payload = "x".repeat(200_000)
        val res = post("/echo", payload)
        assertEquals(200, res.statusCode())
        assertEquals(payload.length, res.body().length)
        assertEquals(payload, res.body())
    }

    // ------------------------------------------------------------- json array

    @Test
    fun `a json array output is one well-formed array`() {
        val res = get("/users/1/orders/list?limit=3")
        assertEquals(200, res.statusCode())
        assertEquals(
            "application/json",
            res.headers().firstValue("content-type").get().substringBefore(';'),
        )

        val body = res.body()
        assertTrue(body.startsWith("[") && body.endsWith("]"), body)

        val items = Json.parseToJsonElement(body).jsonArray
        assertEquals(3, items.size)
        items.forEach { assertEquals(1, it.jsonObject["userId"]!!.jsonPrimitive.int) }
    }

    @Test
    fun `a json array is framed as it is produced, not assembled and then sent`() {
        // Same reasoning as the SSE timing test below: the source is throttled,
        // so a buffered response would deliver the opening element at the same
        // moment as the closing bracket.
        val req = HttpRequest.newBuilder(URI.create(url("/users/1/orders/list?limit=8"))).GET().build()
        val start = System.nanoTime()
        val res = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
        assertEquals(200, res.statusCode())

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

        assertEquals(8, Json.parseToJsonElement(body.toString()).jsonArray.size)
        assertTrue(totalMs >= 250, "stream finished suspiciously fast: ${totalMs}ms")
        assertTrue(
            firstMs < totalMs / 2,
            "first element at ${firstMs}ms of ${totalMs}ms — looks buffered, not streamed",
        )
    }

    @Test
    fun `a json array output documents as an array of the element schema`() {
        val doc = Json.parseToJsonElement(ordersSpec().openApiJson()).jsonObject
        val schema = doc["paths"]!!.jsonObject["/users/{userId}/orders/list"]!!
            .jsonObject["get"]!!.jsonObject["responses"]!!.jsonObject["200"]!!
            .jsonObject["content"]!!.jsonObject["application/json"]!!
            .jsonObject["schema"]!!.jsonObject

        assertEquals("array", schema["type"]!!.jsonPrimitive.content)
        assertEquals(
            "#/components/schemas/Order",
            schema["items"]!!.jsonObject["\$ref"]!!.jsonPrimitive.content,
        )
    }

    // ------------------------------------------------------------- sse + backpressure

    @Test
    fun `server-sent events use the sse framing and event name`() {
        val res = get("/users/1/orders/watch?limit=3")
        assertEquals(200, res.statusCode())
        assertTrue(
            res.headers().firstValue("content-type").get().startsWith("text/event-stream"),
            res.headers().firstValue("content-type").toString(),
        )
        val frames = res.body().split("\n\n").filter { it.isNotBlank() }
        assertEquals(3, frames.size)
        frames.forEach { frame ->
            assertTrue(frame.contains("event: order"), frame)
            val data = frame.lines().first { it.startsWith("data: ") }.removePrefix("data: ")
            assertTrue(Json.parseToJsonElement(data).jsonObject.containsKey("seq"))
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
        assertEquals(200, res.statusCode())

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

        assertEquals(8, frames)
        assertTrue(totalMs >= 600, "stream finished suspiciously fast: ${totalMs}ms")
        assertTrue(
            firstMs < totalMs / 2,
            "first frame at ${firstMs}ms of ${totalMs}ms — looks buffered, not streamed",
        )
    }

    // ------------------------------------------------------------- openapi

    @Test
    fun `serves a coherent openapi document`() {
        val res = get("/openapi.json")
        assertEquals(200, res.statusCode())
        val doc = Json.parseToJsonElement(res.body()).jsonObject

        assertEquals("3.1.0", doc["openapi"]!!.jsonPrimitive.content)
        assertEquals("Orders", doc["info"]!!.jsonObject["title"]!!.jsonPrimitive.content)

        val paths = doc["paths"]!!.jsonObject
        assertTrue(paths.containsKey("/users/{userId}"), paths.keys.toString())
        assertTrue(paths.containsKey("/users/{userId}/orders"), paths.keys.toString())
        assertTrue(paths.containsKey("/users/{userId}/orders/{orderId}"), paths.keys.toString())

        // GET and POST on the same template are merged into one path item.
        val ordersItem = paths["/users/{userId}/orders"]!!.jsonObject
        assertTrue(ordersItem.containsKey("get"))
        assertTrue(ordersItem.containsKey("post"))

        // The streaming endpoint advertises its real media type.
        val streamOk = ordersItem["get"]!!.jsonObject["responses"]!!.jsonObject["200"]!!.jsonObject
        assertTrue(streamOk["content"]!!.jsonObject.containsKey("application/x-ndjson"))

        // Parameters carry their location, requiredness and schema.
        val params = ordersItem["get"]!!.jsonObject["parameters"]!!.jsonArray.map { it.jsonObject }
        val limitParam = params.first { it["name"]!!.jsonPrimitive.content == "limit" }
        assertEquals("query", limitParam["in"]!!.jsonPrimitive.content)
        assertFalse(limitParam["required"]!!.jsonPrimitive.boolean)
        assertEquals("integer", limitParam["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val userIdParam = params.first { it["name"]!!.jsonPrimitive.content == "userId" }
        assertEquals("path", userIdParam["in"]!!.jsonPrimitive.content)
        assertTrue(userIdParam["required"]!!.jsonPrimitive.boolean)
        assertEquals("int64", userIdParam["schema"]!!.jsonObject["format"]!!.jsonPrimitive.content)

        val statusParam = params.first { it["name"]!!.jsonPrimitive.content == "status" }
        val enumValues = statusParam["schema"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("PENDING", "SHIPPED", "DELIVERED", "CANCELLED"), enumValues)

        // Model types are hoisted into components and referenced.
        val schemas = doc["components"]!!.jsonObject["schemas"]!!.jsonObject
        assertTrue(schemas.containsKey("Order"), schemas.keys.toString())
        assertTrue(schemas.containsKey("User"), schemas.keys.toString())
        assertTrue(schemas.containsKey("CreateOrder"), schemas.keys.toString())

        val orderSchema = schemas["Order"]!!.jsonObject
        val required = orderSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(required.contains("id"))
        // quantity has a default in CreateOrder, so it must be optional there
        val createRequired = schemas["CreateOrder"]!!.jsonObject["required"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("item"), createRequired)

        val streamRef = streamOk["content"]!!.jsonObject["application/x-ndjson"]!!
            .jsonObject["schema"]!!.jsonObject["\$ref"]!!.jsonPrimitive.content
        assertEquals("#/components/schemas/Order", streamRef)
    }

    @Test
    fun `declared error responses are documented`() {
        val doc = Json.parseToJsonElement(ordersSpec().openApiJson()).jsonObject
        val getUserOp = doc["paths"]!!.jsonObject["/users/{userId}"]!!.jsonObject["get"]!!.jsonObject
        val responses = getUserOp["responses"]!!.jsonObject
        assertTrue(responses.containsKey("200"))
        assertTrue(responses.containsKey("404"))
        assertEquals("No user with that id", responses["404"]!!.jsonObject["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `serves a swagger ui page at the configured path`() {
        val res = get("/api-docs")
        assertEquals(200, res.statusCode())
        assertTrue(
            res.headers().firstValue("content-type").get().startsWith("text/html"),
            res.headers().firstValue("content-type").toString(),
        )

        val body = res.body()
        assertTrue(body.contains("swagger-ui"), body.take(200))
        // Pointed at the served document rather than a copy of it.
        assertTrue(body.contains("url: '/openapi.json'"), body)
        assertTrue(body.contains("Orders — API reference"), body)

        // The default path is gone, not merely duplicated.
        assertEquals(404, get("/docs").statusCode())
    }

    // ------------------------------------------------------------- hidden

    @Test
    fun `a hidden endpoint is served but not documented`() {
        val res = post("/internal/reindex", "", "X-Api-Key" to "let-me-in")
        assertEquals(202, res.statusCode())

        // Hidden hides the description, not the door: the credential still counts.
        assertEquals(401, post("/internal/reindex", "", "X-Api-Key" to "nope").statusCode())

        val doc = Json.parseToJsonElement(get("/openapi.json").body()).jsonObject
        val paths = doc["paths"]!!.jsonObject
        assertFalse(paths.containsKey("/internal/reindex"), paths.keys.toString())
    }
}

/**
 * The Authorize button only works if the page is told who it is and where the
 * identity provider may send the reader back to.
 */
class SwaggerUiOAuthTest {

    private val oauth = oauth2AuthorizationCode(
        authorizationUrl = "https://id.example.com/authorize",
        tokenUrl = "https://id.example.com/token",
        scopes = mapOf("orders:read" to "Read orders"),
    )

    private val secured = endpoint(noInputs) {
        get("secure")
        security(oauth, "orders:read")
        json<User>()
    }

    private fun serve(docsOAuth: DocsOAuth?, block: (String) -> Unit) {
        val server = Api(
            endpoints = listOf(secured handledNow { User(1, "Ada Lovelace", "ada@example.com") }),
            codecs = JacksonCodecs,
            title = "Orders",
        ).startWithDocs(
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
            assertTrue(page.contains("initOAuth"), page)
            assertTrue(page.contains(""""clientId":"docs-ui""""), page)
            assertTrue(page.contains(""""usePkceWithAuthorizationCodeGrant":true"""), page)
            // Resolved against the origin the reader is on, not a configured host.
            assertTrue(
                page.contains("""oauth2RedirectUrl: window.location.origin + "/api-docs/oauth2-redirect.html""""),
                page,
            )

            val redirect = fetch("$base/api-docs/oauth2-redirect.html")
            assertEquals(200, redirect.statusCode())
            assertTrue(redirect.headers().firstValue("content-type").get().startsWith("text/html"))
            assertTrue(redirect.body().contains("swaggerUIRedirectOauth2"), redirect.body())

            // The requirement itself reaches the document, padlock and all.
            val doc = Json.parseToJsonElement(fetch("$base/openapi.json").body()).jsonObject
            val schemes = doc["components"]!!.jsonObject["securitySchemes"]!!.jsonObject
            assertTrue(schemes.containsKey("oauth2"), schemes.keys.toString())
            assertTrue(
                schemes["oauth2"]!!.jsonObject["flows"]!!.jsonObject.containsKey("authorizationCode"),
            )
        }
    }

    @Test
    fun `without docsOAuth there is no redirect page and no initOAuth`() {
        serve(null) { base ->
            val page = fetch("$base/api-docs").body()
            assertFalse(page.contains("initOAuth"), page)
            assertEquals(404, fetch("$base/api-docs/oauth2-redirect.html").statusCode())
        }
    }
}

/**
 * The docs page has two ways to reach the document, and the fallback exists so
 * that switching off `/openapi.json` does not leave a page pointed at nothing.
 */
class SwaggerUiTest {

    private fun serve(openApi: String?, docs: String?, block: (String) -> Unit) {
        val server = Api(
            endpoints = listOf(
                getUser handledOrFail { id ->
                    Store.user(id)?.let { ok(it) } ?: noSuchUser(ApiError(404, "No user $id"))
                },
            ),
            codecs = JacksonCodecs,
            title = "Orders",
        ).startWithDocs(
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
            assertTrue(body.contains("url: '/openapi.json'"), body)
            assertFalse(body.contains("spec: {"), "the document should not also be inlined")
            assertEquals(200, fetch("$base/openapi.json").statusCode())
        }
    }

    @Test
    fun `without a spec endpoint, the document is embedded in the page`() {
        serve(openApi = null, docs = "/api-docs") { base ->
            assertEquals(404, fetch("$base/openapi.json").statusCode())

            val body = fetch("$base/api-docs").body()
            assertFalse(body.contains("url: '"), "nothing left to fetch, so nothing should be fetched")
            assertTrue(body.contains("spec: {"), body.take(400))

            // Embedded whole, not truncated to something Swagger UI cannot read.
            val spec = body.substringAfter("spec: ").substringBefore(", dom_id")
            val doc = Json.parseToJsonElement(spec).jsonObject
            assertEquals("3.1.0", doc["openapi"]!!.jsonPrimitive.content)
            assertTrue(doc["paths"]!!.jsonObject.containsKey("/users/{userId}"))
        }
    }

    @Test
    fun `a description containing a closing script tag cannot break out of the page`() {
        val hazard = endpoint(noInputs) {
            get("hazard")
            summary = "Closes the tag: </script><script>alert(1)</script>"
            json<User>()
        }
        val server = Api(
            endpoints = listOf(hazard handledNow { User(1, "a", "b") }),
            codecs = JacksonCodecs,
            title = "Hazard",
        ).startWithDocs(
            port = 0,
            systemName = "swagger-escape-test",
            docs = Docs(openApiPath = null, docsPath = "/api-docs"),
        )
        try {
            val body = fetch("${server.baseUrl}/api-docs").body()
            // The literal sequence must not survive into the page.
            assertFalse(body.contains("</script><script>alert"), body)
            assertTrue(body.contains("<\\/script>"), body.substringAfter("spec: ").take(400))
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
            assertEquals(200, sse.statusCode())
            assertTrue(sse.headers().firstValue("content-type").get().startsWith("text/event-stream"))

            // The capture still works for anything else.
            val del = client.send(
                HttpRequest.newBuilder(URI.create("${server.baseUrl}/users/1/orders/42"))
                    .DELETE().header("X-Api-Key", "let-me-in").build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(204, del.statusCode())
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
            assertEquals(200, res.statusCode())
            assertEquals(3, res.body().trim().lines().size)
        } finally {
            server.stop().toCompletableFuture().join()
        }
    }

    @Test
    fun `declaring a path parameter that is not in the path fails at construction`() {
        val stray = pathParam<Long>("stray")
        val e = assertThrows(IllegalStateException::class.java) {
            endpoint(stray) {
                get("things")
                json<User>()
            }
        }
        assertTrue(e.message!!.contains("declares path parameter 'stray'"), e.message)
    }

    @Test
    fun `capturing a path parameter nobody declared fails at construction`() {
        val ignored = pathParam<Long>("ignored")
        val other = queryParam<Int>("other")
        val e = assertThrows(IllegalStateException::class.java) {
            endpoint(other) {
                get("things" / ignored)
                json<User>()
            }
        }
        assertTrue(e.message!!.contains("never declares it as an input"), e.message)
    }

    @Test
    fun `a duplicated path parameter name fails at construction`() {
        val a = pathParam<Long>("dup")
        val b = pathParam<Long>("dup")
        val e = assertThrows(IllegalStateException::class.java) {
            endpoint(a, b) {
                get("things" / a / b)
                json<User>()
            }
        }
        assertTrue(e.message!!.contains("more than once"), e.message)
    }

    @Test
    fun `typed inputs register themselves for decoding and documentation`() {
        // streamOrders never calls query() or header() — endpoint(...) did it.
        assertEquals(listOf("limit", "status"), streamOrders.queries.map { it.name })
        assertEquals(listOf("X-Trace-Id"), streamOrders.headerParams.map { it.name })
        assertEquals(listOf("userId"), streamOrders.pathSpec.captures.map { it.name })
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
        val api = Api(
            // The handler receives AccountId, not Long. Swapping it for another
            // Long-backed id would not compile.
            endpoints = listOf(
                getAccount handledNow { id: AccountId -> User(id.value, "acct-${id.value}", "a@b.c") },
            ),
            codecs = JacksonCodecs,
            title = "Accounts",
        )
        val server = api.start(port = 0, systemName = "wrapper-test")
        try {
            val res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${server.baseUrl}/accounts/77")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, res.statusCode())
            assertEquals(77, Json.parseToJsonElement(res.body()).jsonObject["id"]!!.jsonPrimitive.int)
        } finally {
            server.stop().toCompletableFuture().join()
        }

        val schema = Json.parseToJsonElement(
            ApiSpec(listOf(getAccount), JacksonCodecs).openApiJson(),
        ).jsonObject["paths"]!!.jsonObject["/accounts/{accountId}"]!!.jsonObject["get"]!!
            .jsonObject["parameters"]!!.jsonArray.first().jsonObject["schema"]!!.jsonObject

        assertEquals("integer", schema["type"]!!.jsonPrimitive.content)
        assertEquals("int64", schema["format"]!!.jsonPrimitive.content)
    }
}
