package example.secured

import io.github.matthewjones372.pelican.openapi.oauth2RedirectPath
import io.github.matthewjones372.pelican.openapi.openApiJson
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterAll
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
        basic["type"]!!.jsonPrimitive.content shouldBe "http"
        basic["scheme"]!!.jsonPrimitive.content shouldBe "basic"

        val oauth = schemes["companyIdp"]!!.jsonObject
        oauth["type"]!!.jsonPrimitive.content shouldBe "oauth2"

        val flow = oauth["flows"]!!.jsonObject["authorizationCode"]!!.jsonObject
        flow["authorizationUrl"]!!.jsonPrimitive.content shouldBe "https://id.example.com/oauth2/authorize"
        flow["tokenUrl"]!!.jsonPrimitive.content shouldBe "https://id.example.com/oauth2/token"
        flow["refreshUrl"]!!.jsonPrimitive.content shouldBe "https://id.example.com/oauth2/token"
        flow["scopes"]!!.jsonObject.keys.toList() shouldBe listOf("reports:read", "reports:write", "reports:admin")
    }

    @Test
    fun `an endpoint that says nothing inherits the api-wide requirement`() {
        // The default is written once, at the root of the document, and an
        // operation that does not override it simply has no `security` of its
        // own — which is how a reader (and Swagger UI) is told it applies.
        spec["security"]!!.jsonArray.flatMap { req ->
            req.jsonObject.map { (scheme, scopes) ->
                scheme to scopes.jsonArray.map { it.jsonPrimitive.content }
            }
        } shouldBe listOf("companyIdp" to listOf("reports:read"))
        operation("/reports", "get") shouldNotContainKey "security"
    }

    @Test
    fun `an endpoint that names a scope replaces the default`() {
        requirementsOf(operation("/reports", "post")) shouldBe listOf("companyIdp" to listOf("reports:write"))
    }

    @Test
    fun `two requirements are documented as alternatives`() {
        requirementsOf(operation("/reports/{reportId}", "delete")) shouldBe listOf(
            "companyIdp" to listOf("reports:admin"),
            "staffLogin" to emptyList(),
        )
    }

    @Test
    fun `noSecurity publishes an empty requirement list rather than inheriting`() {
        operation("/health", "get")["security"]!!.jsonArray.shouldBeEmpty()
    }

    @Test
    fun `the failures a credential can cause are documented alongside it`() {
        val responses = operation("/internal/usage", "get")["responses"]!!.jsonObject
        responses.keys shouldContain "401"
        responses.keys shouldContain "403"
    }

    // ------------------------------------------------------ the docs page

    @Test
    fun `the docs page signs in as its own client and is sent back to the redirect it serves`() {
        val page = send("GET", "/api-docs").body()
        page shouldContain "initOAuth"
        page shouldContain """"clientId":"reports-docs-ui""""
        page shouldContain """"usePkceWithAuthorizationCodeGrant":true"""
        page shouldContain """"audience":"https://api.example.com/reports""""
        page shouldContain "oauth2RedirectUrl"
        page shouldContain oauth2RedirectPath(DOCS_PATH)

        // Served by this service, on the same origin as the page — which is the
        // URL the identity provider has to have registered.
        val redirect = send("GET", oauth2RedirectPath(DOCS_PATH))
        redirect.statusCode() shouldBe 200
        redirect.body() shouldContain "swaggerUIRedirectOauth2"
    }

    // ------------------------------------------------------ the enforcement

    @Test
    fun `an open endpoint needs nothing`() {
        send("GET", "/health").statusCode() shouldBe 200
    }

    @Test
    fun `no credential is a 401`() {
        send("GET", "/reports").statusCode() shouldBe 401
        send("GET", "/internal/usage").statusCode() shouldBe 401
    }

    @Test
    fun `a credential that does not check out is a 401, not a 403`() {
        send("GET", "/reports", null, bearer("made-up")).statusCode() shouldBe 401
        send("GET", "/internal/usage", null, basic("ops", "wrong")).statusCode() shouldBe 401
    }

    @Test
    fun `a token with the scope gets through`() {
        val res = send("GET", "/reports", null, bearer("demo-reader"))
        res.statusCode() shouldBe 200
        // Not a count: the store is shared with the tests below, which file and
        // withdraw reports in whatever order JUnit runs them.
        withClue(res.body()) { Json.parseToJsonElement(res.body()).jsonArray.shouldNotBeEmpty() }
    }

    @Test
    fun `a token without the scope is a 403 naming what is missing`() {
        val res = send("POST", "/reports", """{"title":"Cold start","body":"90s"}""", bearer("demo-reader"))
        res.statusCode() shouldBe 403
        res.body() shouldContain "reports:write"
    }

    @Test
    fun `the author comes from the token, not from the caller's body`() {
        val res = send("POST", "/reports", """{"title":"Cold start","body":"90s"}""", bearer("demo-writer"))
        res.statusCode() shouldBe 201
        val obj = Json.parseToJsonElement(res.body()).jsonObject
        obj["author"]!!.jsonPrimitive.content shouldBe "grace@example.com"
        obj["title"]!!.jsonPrimitive.content shouldBe "Cold start"
    }

    @Test
    fun `either alternative satisfies an endpoint that documents two`() {
        send("DELETE", "/reports/1", null, bearer("demo-admin")).statusCode() shouldBe 204
        send("DELETE", "/reports/2", null, basic("ops", "s3cret")).statusCode() shouldBe 204
        send("DELETE", "/reports/3", null, bearer("demo-writer")).statusCode() shouldBe 403
    }

    @Test
    fun `basic auth reaches the endpoint that only accepts it`() {
        val res = send("GET", "/internal/usage", null, basic("sre", "pager-duty"))
        res.statusCode() shouldBe 200
        Json.parseToJsonElement(res.body()).jsonObject shouldContainKey "reports"
    }

    @Test
    fun `an oauth token is not an operator login`() {
        send("GET", "/internal/usage", null, bearer("demo-admin")).statusCode() shouldBe 403
    }

    // -------------------------------------------- what the answer carries

    @Test
    fun `the 401 says which credential to present`() {
        val res = send("GET", "/reports")
        res.statusCode() shouldBe 401
        // Without this a browser has nothing to prompt with, and a client has
        // to guess which of the two schemes this endpoint wanted.
        val challenge = res.headers().firstValue("WWW-Authenticate").orElse(null)
        withClue(res.headers().map().toString()) { challenge.shouldNotBeNull() }
        challenge shouldContain "Bearer"
        challenge shouldContain "Basic"
    }

    @Test
    fun `a filed report answers with the declared Location header`() {
        val res = send(
            "POST", "/reports",
            """{"title":"Cache stampede","body":"On deploy."}""",
            bearer("demo-writer"),
        )
        res.statusCode() shouldBe 201

        val id = Json.parseToJsonElement(res.body()).jsonObject["id"]!!.jsonPrimitive.content
        res.headers().firstValue("Location").orElse(null) shouldBe "/reports/$id"
    }

    @Test
    fun `the Location header is documented on the response that carries it`() {
        val header = operation("/reports", "post")["responses"]!!.jsonObject["201"]!!
            .jsonObject["headers"]!!.jsonObject["Location"]!!.jsonObject

        header["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "string"
        header["description"]!!.jsonPrimitive.content shouldBe "Where the report that was just filed lives"
    }

    @Test
    fun `an optional header is documented as optional`() {
        val header = operation("/reports", "post")["responses"]!!.jsonObject["429"]!!
            .jsonObject["headers"]!!.jsonObject["Retry-After"]!!.jsonObject

        header["required"]!!.jsonPrimitive.content.toBoolean() shouldBe false
        header["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "integer"
    }

    @Test
    fun `every documented endpoint is bound, and the Api says so at startup`() {
        // `securedApi()` passes `covers = allSecuredEndpoints`. If a handler
        // were dropped from `securedRoutes` this suite would fail to start
        // rather than reporting a 404 on one endpoint.
        securedApi().endpoints.size shouldBe allSecuredEndpoints.size
    }
}
