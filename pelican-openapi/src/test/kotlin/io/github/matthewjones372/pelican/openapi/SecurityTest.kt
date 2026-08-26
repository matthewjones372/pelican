package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.SecurityRequirement
import io.github.matthewjones372.pelican.apiKeyHeader
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.empty
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.oauth2AuthorizationCode
import io.github.matthewjones372.pelican.openapi.div
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.requires
import io.github.matthewjones372.pelican.text
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

class SecurityTest {

    object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) {
                components.register(name, jsonObj { "type" to "object" })
            }
            return components.ref(name)
        }
    }

    data class Widget(val id: Long)

    private val oauth = oauth2AuthorizationCode(
        authorizationUrl = "https://id.example.com/authorize",
        tokenUrl = "https://id.example.com/token",
        refreshUrl = "https://id.example.com/token",
        scopes = mapOf(
            "widgets:read" to "Read widgets",
            "widgets:write" to "Create and delete widgets",
        ),
        name = "widgets-oauth",
    )

    private val widgetId = pathParam<Long>("widgetId")

    private val getWidget = endpoint(widgetId) {
        get("widgets" / widgetId)
        security(oauth, "widgets:read")
        json<Widget>()
    }

    private val deleteWidget = endpoint(widgetId) {
        delete("widgets" / widgetId)
        security(oauth, "widgets:read", "widgets:write")
        empty()
    }

    private val health = endpoint {
        get("health")
        noSecurity()
        text()
    }

    private val debugDump = endpoint {
        get("internal" / "dump")
        hidden = true
        security(oauth, "widgets:write")
        text()
    }

    private fun spec(vararg eps: Endpoint<*, *>, default: List<SecurityRequirement> = emptyList()) =
        apiSpec(eps.toList(), Schemas) {
            security = default
        }

    @Test
    fun `a hidden endpoint is left out of the document`() {
        val doc = spec(getWidget, debugDump).openApi()
        val paths = doc / "paths"
        paths.keys() shouldContain "/widgets/{widgetId}"
        withClue(paths.keys().toString()) { paths.keys() shouldNotContain "/internal/dump" }
    }

    @Test
    fun `a scheme only a hidden endpoint uses is not published either`() {
        val doc = spec(debugDump).openApi()
        (doc / "components").keys() shouldNotContain "securitySchemes"
    }

    @Test
    fun `an endpoint's requirement names the scheme and its scopes`() {
        val doc = spec(getWidget, deleteWidget).openApi()

        val read = (doc / "paths" / "/widgets/{widgetId}" / "get" / "security").arr()
        read.size shouldBe 1
        (read[0] / "widgets-oauth").strings() shouldBe listOf("widgets:read")

        val write = (doc / "paths" / "/widgets/{widgetId}" / "delete" / "security").arr()
        (write[0] / "widgets-oauth").strings() shouldBe listOf("widgets:read", "widgets:write")
    }

    @Test
    fun `the oauth2 scheme lands in components with its flow and scopes`() {
        val doc = spec(getWidget).openApi()
        val scheme = doc / "components" / "securitySchemes" / "widgets-oauth"

        (scheme / "type").str() shouldBe "oauth2"
        val flow = scheme / "flows" / "authorizationCode"
        (flow / "authorizationUrl").str() shouldBe "https://id.example.com/authorize"
        (flow / "tokenUrl").str() shouldBe "https://id.example.com/token"
        (flow / "refreshUrl").str() shouldBe "https://id.example.com/token"
        (flow / "scopes").keys() shouldBe setOf("widgets:read", "widgets:write")
        (flow / "scopes" / "widgets:read").str() shouldBe "Read widgets"
    }

    @Test
    fun `an api-wide requirement is documented once, and endpoints inherit it`() {
        val doc = spec(getWidget, health, default = listOf(oauth.requires("widgets:read"))).openApi()

        ((doc / "security").arr()[0] / "widgets-oauth").strings() shouldBe listOf("widgets:read")
        // The endpoint that says nothing says nothing in the document either —
        // that is what inheriting the default looks like.
        (doc / "paths" / "/health" / "get" / "summary").shouldBeNull()
        // noSecurity() is an explicit override, and OpenAPI spells it `[]`.
        (doc / "paths" / "/health" / "get" / "security").arr().shouldBeEmpty()
    }

    @Test
    fun `a scope the scheme never declared fails when the endpoint is built`() {
        val e = shouldThrow<IllegalStateException> {
            endpoint {
                get("widgets")
                security(oauth, "widgets:delete")
                text()
            }
        }
        e.message shouldContain "widgets:delete"
        e.message shouldContain "widgets:read"
    }

    @Test
    fun `scopes can be declared as bare names`() {
        val plain = oauth2AuthorizationCode(
            authorizationUrl = "https://id.example.com/authorize",
            tokenUrl = "https://id.example.com/token",
            scopes = listOf("widgets:read", "widgets:write"),
        )
        val ep = endpoint {
            get("widgets")
            security(plain, "widgets:write")
            text()
        }

        val flow = spec(ep).openApi() / "components" / "securitySchemes" / "oauth2" /
            "flows" / "authorizationCode"

        // Still one checkbox per scope in the Authorize dialog, in this order.
        (flow / "scopes").keys().toList() shouldBe listOf("widgets:read", "widgets:write")
        (flow / "scopes" / "widgets:read").str() shouldBe ""

        // A requirement names scopes either way — that half never carried
        // descriptions.
        val req = (spec(ep).openApi() / "paths" / "/widgets" / "get" / "security").arr()
        (req[0] / "oauth2").strings() shouldBe listOf("widgets:write")
    }

    @Test
    fun `a non-oauth scheme takes no scopes`() {
        val bearer = bearerAuth()
        shouldThrow<IllegalStateException> { bearer.requires("anything") }

        val doc = spec(
            endpoint {
                get("widgets")
                security(bearer)
                text()
            },
        ).openApi()
        val scheme = doc / "components" / "securitySchemes" / "bearerAuth"
        (scheme / "type").str() shouldBe "http"
        (scheme / "scheme").str() shouldBe "bearer"
        (scheme / "bearerFormat").str() shouldBe "JWT"
    }

    @Test
    fun `an api key scheme documents where the key travels`() {
        val doc = spec(
            endpoint {
                get("widgets")
                security(apiKeyHeader("X-Api-Key"))
                text()
            },
        ).openApi()
        val scheme = doc / "components" / "securitySchemes" / "apiKey"
        (scheme / "type").str() shouldBe "apiKey"
        (scheme / "in").str() shouldBe "header"
        (scheme / "name").str() shouldBe "X-Api-Key"
    }

    @Test
    fun `two different schemes cannot share one name`() {
        val a = bearerAuth(name = "auth")
        val b = apiKeyHeader("X-Api-Key", name = "auth")
        val one = endpoint { get("a"); security(a); text() }
        val two = endpoint { get("b"); security(b); text() }

        val e = shouldThrow<IllegalStateException> { spec(one, two).openApi() }
        e.message shouldContain "'auth'"
    }
}
