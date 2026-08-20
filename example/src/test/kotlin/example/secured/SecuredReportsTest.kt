package example.secured

import dev.pelican.openapi.oauth2RedirectPath
import dev.pelican.openapi.openApiJson
import dev.pelican.pekko.PelicanServer
import dev.pelican.pekko.docs.startWithDocs
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
import java.util.Base64

/**
 * The two halves of the security example, checked against each other: what the
 * document promises a caller must present, and what the running service does
 * when they present it — or don't.
 *
 * The enforcement half is one filter that reads `endpoint.security`, so these
 * assertions are also the evidence that the filter and the padlock cannot come
 * apart: every case below is stated once against the document and once against
 * the wire.
 */
class SecuredReportsTest {

    companion object {
        private lateinit var server: PelicanServer
        private val client: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()

        @BeforeAll
        @JvmStatic
        fun boot() {
            server = securedApi().startWithDocs(
                port = 0,
                systemName = "secured-reports-test",
                docs = securedDocs,
            )
        }

        @AfterAll
        @JvmStatic
        fun shutdown() {
            server.stop().toCompletableFuture().join()
        }
    }

    private fun url(path: String) = "${server.baseUrl}$path"

    private fun send(
        method: String,
        path: String,
        body: String? = null,
        vararg headers: Pair<String, String>,
    ): HttpResponse<String> {
        val publisher =
            if (body == null) HttpRequest.BodyPublishers.noBody()
            else HttpRequest.BodyPublishers.ofString(body)
        val b = HttpRequest.newBuilder(URI.create(url(path)))
            .method(method, publisher)
            .header("Content-Type", "application/json")
        headers.forEach { (k, v) -> b.header(k, v) }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun bearer(token: String) = "Authorization" to "Bearer $token"

    private fun basic(user: String, password: String) =
        "Authorization" to "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    private val spec: JsonObject by lazy {
        Json.parseToJsonElement(securedSpec().openApiJson()).jsonObject
    }

    private fun operation(path: String, method: String): JsonObject =
        spec["paths"]!!.jsonObject[path]!!.jsonObject[method]!!.jsonObject

    /** The scheme names a requirement list mentions, in order. */
    private fun requirementsOf(op: JsonObject): List<Pair<String, List<String>>> =
        op["security"]!!.jsonArray.flatMap { req ->
            req.jsonObject.map { (scheme, scopes) ->
                scheme to scopes.jsonArray.map { it.jsonPrimitive.content }
            }
        }

    // ------------------------------------------------------- the document

    @Test
    fun `both schemes reach components, described as what they are`() {
        val schemes = spec["components"]!!.jsonObject["securitySchemes"]!!.jsonObject

        val basic = schemes["staffLogin"]!!.jsonObject
        assertEquals("http", basic["type"]!!.jsonPrimitive.content)
        assertEquals("basic", basic["scheme"]!!.jsonPrimitive.content)

        val oauth = schemes["companyIdp"]!!.jsonObject
        assertEquals("oauth2", oauth["type"]!!.jsonPrimitive.content)

        val flow = oauth["flows"]!!.jsonObject["authorizationCode"]!!.jsonObject
        assertEquals(
            "https://id.example.com/oauth2/authorize",
            flow["authorizationUrl"]!!.jsonPrimitive.content,
        )
        assertEquals("https://id.example.com/oauth2/token", flow["tokenUrl"]!!.jsonPrimitive.content)
        assertEquals("https://id.example.com/oauth2/token", flow["refreshUrl"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("reports:read", "reports:write", "reports:admin"),
            flow["scopes"]!!.jsonObject.keys.toList(),
        )
    }

    @Test
    fun `an endpoint that says nothing inherits the api-wide requirement`() {
        // The default is written once, at the root of the document, and an
        // operation that does not override it simply has no `security` of its
        // own — which is how a reader (and Swagger UI) is told it applies.
        assertEquals(
            listOf("companyIdp" to listOf("reports:read")),
            spec["security"]!!.jsonArray.flatMap { req ->
                req.jsonObject.map { (scheme, scopes) ->
                    scheme to scopes.jsonArray.map { it.jsonPrimitive.content }
                }
            },
        )
        assertFalse(operation("/reports", "get").containsKey("security"))
    }

    @Test
    fun `an endpoint that names a scope replaces the default`() {
        assertEquals(
            listOf("companyIdp" to listOf("reports:write")),
            requirementsOf(operation("/reports", "post")),
        )
    }

    @Test
    fun `two requirements are documented as alternatives`() {
        assertEquals(
            listOf(
                "companyIdp" to listOf("reports:admin"),
                "staffLogin" to emptyList(),
            ),
            requirementsOf(operation("/reports/{reportId}", "delete")),
        )
    }

    @Test
    fun `noSecurity publishes an empty requirement list rather than inheriting`() {
        assertTrue(operation("/health", "get")["security"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `the failures a credential can cause are documented alongside it`() {
        val responses = operation("/internal/usage", "get")["responses"]!!.jsonObject
        assertTrue("401" in responses, responses.keys.toString())
        assertTrue("403" in responses, responses.keys.toString())
    }

    // ------------------------------------------------------ the docs page

    @Test
    fun `the docs page signs in as its own client and is sent back to the redirect it serves`() {
        val page = send("GET", "/api-docs").body()
        assertTrue(page.contains("initOAuth"), page)
        assertTrue(page.contains(""""clientId":"reports-docs-ui""""), page)
        assertTrue(page.contains(""""usePkceWithAuthorizationCodeGrant":true"""), page)
        assertTrue(page.contains(""""audience":"https://api.example.com/reports""""), page)
        assertTrue(page.contains("oauth2RedirectUrl"), page)
        assertTrue(page.contains(oauth2RedirectPath(DOCS_PATH)), page)

        // Served by this service, on the same origin as the page — which is the
        // URL the identity provider has to have registered.
        val redirect = send("GET", oauth2RedirectPath(DOCS_PATH))
        assertEquals(200, redirect.statusCode())
        assertTrue(redirect.body().contains("swaggerUIRedirectOauth2"), redirect.body())
    }

    // ------------------------------------------------------ the enforcement

    @Test
    fun `an open endpoint needs nothing`() {
        assertEquals(200, send("GET", "/health").statusCode())
    }

    @Test
    fun `no credential is a 401`() {
        assertEquals(401, send("GET", "/reports").statusCode())
        assertEquals(401, send("GET", "/internal/usage").statusCode())
    }

    @Test
    fun `a credential that does not check out is a 401, not a 403`() {
        assertEquals(401, send("GET", "/reports", null, bearer("made-up")).statusCode())
        assertEquals(401, send("GET", "/internal/usage", null, basic("ops", "wrong")).statusCode())
    }

    @Test
    fun `a token with the scope gets through`() {
        val res = send("GET", "/reports", null, bearer("demo-reader"))
        assertEquals(200, res.statusCode())
        // Not a count: the store is shared with the tests below, which file and
        // withdraw reports in whatever order JUnit runs them.
        assertTrue(Json.parseToJsonElement(res.body()).jsonArray.isNotEmpty(), res.body())
    }

    @Test
    fun `a token without the scope is a 403 naming what is missing`() {
        val res = send("POST", "/reports", """{"title":"Cold start","body":"90s"}""", bearer("demo-reader"))
        assertEquals(403, res.statusCode())
        assertTrue(res.body().contains("reports:write"), res.body())
    }

    @Test
    fun `the author comes from the token, not from the caller's body`() {
        val res = send("POST", "/reports", """{"title":"Cold start","body":"90s"}""", bearer("demo-writer"))
        assertEquals(201, res.statusCode())
        val obj = Json.parseToJsonElement(res.body()).jsonObject
        assertEquals("grace@example.com", obj["author"]!!.jsonPrimitive.content)
        assertEquals("Cold start", obj["title"]!!.jsonPrimitive.content)
    }

    @Test
    fun `either alternative satisfies an endpoint that documents two`() {
        assertEquals(204, send("DELETE", "/reports/1", null, bearer("demo-admin")).statusCode())
        assertEquals(204, send("DELETE", "/reports/2", null, basic("ops", "s3cret")).statusCode())
        assertEquals(403, send("DELETE", "/reports/3", null, bearer("demo-writer")).statusCode())
    }

    @Test
    fun `basic auth reaches the endpoint that only accepts it`() {
        val res = send("GET", "/internal/usage", null, basic("sre", "pager-duty"))
        assertEquals(200, res.statusCode())
        assertTrue(Json.parseToJsonElement(res.body()).jsonObject.containsKey("reports"))
    }

    @Test
    fun `an oauth token is not an operator login`() {
        assertEquals(403, send("GET", "/internal/usage", null, bearer("demo-admin")).statusCode())
    }

    // -------------------------------------------- what the answer carries

    @Test
    fun `the 401 says which credential to present`() {
        val res = send("GET", "/reports")
        assertEquals(401, res.statusCode())
        // Without this a browser has nothing to prompt with, and a client has
        // to guess which of the two schemes this endpoint wanted.
        val challenge = res.headers().firstValue("WWW-Authenticate").orElse(null)
        assertNotNull(challenge, res.headers().map().toString())
        assertTrue(challenge.contains("Bearer"), challenge)
        assertTrue(challenge.contains("Basic"), challenge)
    }

    @Test
    fun `a filed report answers with the declared Location header`() {
        val res = send(
            "POST", "/reports",
            """{"title":"Cache stampede","body":"On deploy."}""",
            bearer("demo-writer"),
        )
        assertEquals(201, res.statusCode())

        val id = Json.parseToJsonElement(res.body()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("/reports/$id", res.headers().firstValue("Location").orElse(null))
    }

    @Test
    fun `the Location header is documented on the response that carries it`() {
        val header = operation("/reports", "post")["responses"]!!.jsonObject["201"]!!
            .jsonObject["headers"]!!.jsonObject["Location"]!!.jsonObject

        assertEquals("string", header["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "Where the report that was just filed lives",
            header["description"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `an optional header is documented as optional`() {
        val header = operation("/reports", "post")["responses"]!!.jsonObject["429"]!!
            .jsonObject["headers"]!!.jsonObject["Retry-After"]!!.jsonObject

        assertEquals(false, header["required"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("integer", header["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `every documented endpoint is bound, and the Api says so at startup`() {
        // `securedApi()` passes `covers = allSecuredEndpoints`. If a handler
        // were dropped from `securedRoutes` this suite would fail to start
        // rather than reporting a 404 on one endpoint.
        assertEquals(allSecuredEndpoints.size, securedApi().endpoints.size)
    }
}
