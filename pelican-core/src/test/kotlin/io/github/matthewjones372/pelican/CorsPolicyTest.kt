package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class CorsPolicyTest {

    private val userId = pathParam<Long>("userId")
    private val trace = headerParam<String>("X-Trace-Id").optional()
    private val token = bearerAuth()

    private val getUser = endpoint(userId) {
        get("users" / userId)
        json<String>()
    }

    private val updateUser = endpoint(userId, trace, jsonBody<String>()) {
        put("users" / userId)
        security(token)
        json<String>()
    }

    private val health = endpoint {
        get("health")
        text()
    }

    private fun api(cors: Cors): Api = api(
        endpoints = listOf(getUser, updateUser, health).map { ep ->
            ServerEndpoint(ep) { CompletableFuture.completedStage(null) }
        },
    ) {
        this.cors = cors
    }

    private fun policy(cors: Cors): CorsPolicy = checkNotNull(api(cors).corsPolicy())

    private val app = "https://app.example.com"

    // ------------------------------------------------------------ real requests

    @Test
    fun `an API with no cors has no policy at all`() {
        api(
            listOf(ServerEndpoint(health) { CompletableFuture.completedStage(null) }),
        ).corsPolicy().shouldBeNull()
    }

    @Test
    fun `a listed origin is echoed back, and the answer varies by origin`() {
        val headers = policy(cors(app)).actualResponseHeaders(app).toMap()

        headers["Access-Control-Allow-Origin"] shouldBe app
        headers["Vary"] shouldBe "Origin"
    }

    @Test
    fun `an origin nobody listed is told nothing, which is what blocks it`() {
        val headers = policy(cors(app)).actualResponseHeaders("https://evil.test").toMap()

        headers["Access-Control-Allow-Origin"].shouldBeNull()
        // The refusal still varies by origin, or a cache in front of the
        // service could serve this answer to the origin that is allowed.
        headers["Vary"] shouldBe "Origin"
    }

    @Test
    fun `a request with no Origin is not a cross-origin request`() {
        val headers = policy(cors(app)).actualResponseHeaders(null).toMap()

        headers["Access-Control-Allow-Origin"].shouldBeNull()
        headers["Vary"] shouldBe "Origin"
    }

    @Test
    fun `any origin answers a star, and needs no Vary because the answer never differs`() {
        val headers = policy(corsAnyOrigin()).actualResponseHeaders(app).toMap()

        headers["Access-Control-Allow-Origin"] shouldBe "*"
        headers["Vary"].shouldBeNull()
    }

    @Test
    fun `exposed headers and credentials are carried on the real response`() {
        val headers = policy(
            cors(app, exposedHeaders = listOf("X-Request-Id"), allowCredentials = true),
        ).actualResponseHeaders(app).toMap()

        headers["Access-Control-Allow-Credentials"] shouldBe "true"
        headers["Access-Control-Expose-Headers"] shouldBe "X-Request-Id"
    }

    @Test
    fun `credentials and a wildcard origin is rejected where it is written`() {
        val failure = shouldThrow<IllegalArgumentException> {
            cors(CorsOrigins.Any, allowCredentials = true)
        }

        failure.message!! shouldContain "credentials"
    }

    @Test
    fun `a predicate decides for itself`() {
        val subdomains = CorsOrigins.Matching("https://*.example.com") { it.endsWith(".example.com") }

        policy(cors(subdomains)).actualResponseHeaders(app).toMap()["Access-Control-Allow-Origin"] shouldBe app
        policy(cors(subdomains)).actualResponseHeaders("https://example.test")
            .toMap()["Access-Control-Allow-Origin"].shouldBeNull()
    }

    // ------------------------------------------------------------- preflight

    @Test
    fun `the methods offered are the methods described for that path`() {
        val allowed = policy(cors(app)).preflight(app, "PUT", "/users/7") as CorsPreflight.Allowed
        val headers = allowed.headers.toMap()

        headers["Access-Control-Allow-Methods"] shouldBe "GET, PUT"
        headers["Access-Control-Allow-Origin"] shouldBe app
        headers["Access-Control-Max-Age"] shouldBe "600"
    }

    @Test
    fun `the headers offered are the ones the endpoints on that path declare`() {
        val allowed = policy(cors(app)).preflight(app, "PUT", "/users/7") as CorsPreflight.Allowed

        allowed.headers.toMap()["Access-Control-Allow-Headers"] shouldBe "X-Trace-Id, Content-Type, Authorization"
    }

    @Test
    fun `a preflight for one method is not told about another method's headers`() {
        val allowed = policy(cors(app)).preflight(app, "GET", "/users/7") as CorsPreflight.Allowed

        // GET on this path declares no header, no body and no scheme, so there
        // is nothing to allow — and nothing invented.
        allowed.headers.toMap()["Access-Control-Allow-Headers"].shouldBeNull()
    }

    @Test
    fun `an API-wide scheme reaches an endpoint that never mentions one`() {
        val secured = api(
            endpoints = listOf(ServerEndpoint(getUser) { CompletableFuture.completedStage(null) }),
        ) {
            cors = cors(app)
            security = listOf(token.requires())
        }
        val allowed = checkNotNull(secured.corsPolicy())
            .preflight(app, "GET", "/users/7") as CorsPreflight.Allowed

        allowed.headers.toMap()["Access-Control-Allow-Headers"] shouldBe "Authorization"
    }

    @Test
    fun `a header no description could know about is added, not substituted`() {
        val allowed = policy(cors(app, additionalAllowedHeaders = listOf("X-Tenant")))
            .preflight(app, "PUT", "/users/7") as CorsPreflight.Allowed

        allowed.headers.toMap()["Access-Control-Allow-Headers"] shouldBe
            "X-Trace-Id, Content-Type, Authorization, X-Tenant"
    }

    @Test
    fun `a method nobody described on that path is refused`() {
        val refused = policy(cors(app)).preflight(app, "DELETE", "/users/7") as CorsPreflight.Refused

        refused.reason shouldContain "DELETE"
        refused.reason shouldContain "GET, PUT"
    }

    @Test
    fun `an origin nobody listed is refused before any method is considered`() {
        val refused = policy(cors(app)).preflight("https://evil.test", "GET", "/users/7") as CorsPreflight.Refused

        refused.reason shouldContain "evil.test"
    }

    @Test
    fun `an OPTIONS that is not a preflight is left to the router`() {
        val policy = policy(cors(app))

        policy.preflight(null, "GET", "/users/7") shouldBe CorsPreflight.NotPreflight
        policy.preflight(app, null, "/users/7") shouldBe CorsPreflight.NotPreflight
        policy.preflight(app, "GET", "/nowhere") shouldBe CorsPreflight.NotPreflight
    }

    @Test
    fun `a path template matches on shape, whatever the capture holds`() {
        val policy = policy(cors(app))

        policy.preflight(app, "GET", "/users/not-a-number").shouldBeInstanceOf<CorsPreflight.Allowed>()
        policy.preflight(app, "GET", "/users/7/orders") shouldBe CorsPreflight.NotPreflight
    }
}
