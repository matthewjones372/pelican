package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * The builders in `Security.kt`, most of which nothing in this repository
 * called.
 *
 * They are published, they are in the reference manual, and a caller was the
 * first thing to run several of them. The scope overloads are the ones worth
 * the trouble: they take a `List<String>` and build the `Map` OpenAPI models,
 * which is a place to be wrong that reading does not catch.
 */
class SecuritySchemesTest {

    // ------------------------------------------------------------------ http

    @Test
    fun `bearer auth carries the format callers document it with`() {
        val scheme = bearerAuth()
        scheme.scheme shouldBe "bearer"
        scheme.bearerFormat shouldBe "JWT"
        scheme.name shouldBe "bearerAuth"
    }

    @Test
    fun `basic auth names no format, because there is none to name`() {
        basicAuth().let {
            it.scheme shouldBe "basic"
            it.bearerFormat shouldBe null
        }
    }

    @Test
    fun `any registered http scheme is describable, named after itself by default`() {
        httpAuth("digest").let {
            it.scheme shouldBe "digest"
            it.name shouldBe "digest"
        }
    }

    // ---------------------------------------------------------------- apiKey

    @Test
    fun `an api key is describable in each of the three places one travels`() {
        apiKeyHeader("X-Api-Key").location shouldBe "header"
        apiKeyQuery("api_key").location shouldBe "query"
        apiKeyCookie("session").location shouldBe "cookie"
    }

    @Test
    fun `and carries the parameter name it is actually read from`() {
        apiKeyHeader("X-Api-Key").paramName shouldBe "X-Api-Key"
    }

    // ------------------------------------------------------------- openIdConnect

    @Test
    fun `openid connect carries its discovery url`() {
        openIdConnect("https://idp.example.com/.well-known/openid-configuration").let {
            it.openIdConnectUrl shouldContain "openid-configuration"
            it.name shouldBe "openIdConnect"
        }
    }

    // ----------------------------------------------------------------- oauth2

    @Test
    fun `each flow is describable and declares the scopes it grants`() {
        val scopes = mapOf("reports:read" to "Read reports")

        oauth2AuthorizationCode("https://a", "https://t", scopes).flows.single()
            .shouldBeInstanceOf<OAuthFlow.AuthorizationCode>()
        oauth2ClientCredentials("https://t", scopes).flows.single()
            .shouldBeInstanceOf<OAuthFlow.ClientCredentials>()
        oauth2Password("https://t", scopes).flows.single()
            .shouldBeInstanceOf<OAuthFlow.Password>()
        oauth2Implicit("https://a", scopes).flows.single()
            .shouldBeInstanceOf<OAuthFlow.Implicit>()
    }

    @Test
    fun `a flow keeps the urls it was given`() {
        val flow = oauth2AuthorizationCode(
            authorizationUrl = "https://idp/authorize",
            tokenUrl = "https://idp/token",
            scopes = mapOf("a" to "A"),
            refreshUrl = "https://idp/refresh",
        ).flows.single() as OAuthFlow.AuthorizationCode

        flow.authorizationUrl shouldBe "https://idp/authorize"
        flow.tokenUrl shouldBe "https://idp/token"
        flow.refreshUrl shouldBe "https://idp/refresh"
    }

    @Test
    fun `scopes given as bare names become the map OpenAPI models, in the order given`() {
        // Swagger UI builds its checkboxes from this map and shows the value
        // beside each name, so an empty description is what "no description"
        // has to look like rather than a missing entry.
        val scheme = oauth2ClientCredentials("https://t", listOf("orders:read", "orders:write"))

        scheme.flows.single().scopes.keys.toList() shouldContainExactly
            listOf("orders:read", "orders:write")
        scheme.flows.single().scopes.values.toSet() shouldBe setOf("")
    }

    @Test
    fun `every bare-name overload builds the same map`() {
        listOf(
            oauth2AuthorizationCode("https://a", "https://t", listOf("s")),
            oauth2ClientCredentials("https://t", listOf("s")),
            oauth2Password("https://t", listOf("s")),
            oauth2Implicit("https://a", listOf("s")),
        ).forEach { it.flows.single().scopes shouldBe mapOf("s" to "") }
    }

    @Test
    fun `several flows can sit under one scheme, and the scheme declares all their scopes`() {
        val scheme = oauth2(
            flows = listOf(
                oauth2AuthorizationCode("https://a", "https://t", listOf("read")).flows.single(),
                oauth2ClientCredentials("https://t", listOf("write")).flows.single(),
            ),
        )

        scheme.declaredScopes shouldContainExactly setOf("read", "write")
    }

    // ----------------------------------------------------------- requirements

    @Test
    fun `requiring a scope the scheme declares is fine`() {
        val scheme = oauth2ClientCredentials("https://t", listOf("orders:read"))
        scheme.requires("orders:read").scopes shouldContainExactly listOf("orders:read")
    }

    @Test
    fun `requiring one it never declared names what it does declare`() {
        val scheme = oauth2ClientCredentials("https://t", listOf("orders:read"))

        shouldThrow<IllegalStateException> { scheme.requires("orders:write") }
            .message!! shouldContain "orders:read"
    }

    @Test
    fun `a scheme with no scopes at all says so rather than listing nothing`() {
        shouldThrow<IllegalStateException> { bearerAuth().requires("anything") }
            .message!! shouldContain "<none>"
    }

    @Test
    fun `two different schemes under one name is refused, since half the padlocks would lie`() {
        val one = apiKeyHeader("X-Api-Key", name = "key")
        val other = apiKeyQuery("api_key", name = "key")

        shouldThrow<IllegalStateException> {
            securitySchemesOf(listOf(one.requires(), other.requires()))
        }.message!! shouldContain "key"
    }

    @Test
    fun `the same scheme referenced twice is collected once`() {
        val scheme = bearerAuth()
        securitySchemesOf(listOf(scheme.requires(), scheme.requires())).size shouldBe 1
    }
}
