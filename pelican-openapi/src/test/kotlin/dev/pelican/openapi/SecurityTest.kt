package dev.pelican.openapi

import dev.pelican.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Two questions the document has to answer: which endpoints are published at
 * all, and what a caller must present to use them.
 */
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

    private val health = endpoint(noInputs) {
        get("health")
        noSecurity()
        text()
    }

    private val debugDump = endpoint(noInputs) {
        get("internal" / "dump")
        hidden = true
        security(oauth, "widgets:write")
        text()
    }

    private fun spec(vararg eps: Endpoint<*, *>, default: List<SecurityRequirement> = emptyList()) =
        ApiSpec(endpoints = eps.toList(), schemas = Schemas, security = default)

    @Test
    fun `a hidden endpoint is left out of the document`() {
        val doc = spec(getWidget, debugDump).openApi()
        val paths = doc / "paths"
        assertTrue("/widgets/{widgetId}" in paths.keys())
        assertFalse("/internal/dump" in paths.keys(), paths.keys().toString())
    }

    @Test
    fun `a scheme only a hidden endpoint uses is not published either`() {
        val doc = spec(debugDump).openApi()
        assertFalse("securitySchemes" in (doc / "components").keys())
    }

    @Test
    fun `an endpoint's requirement names the scheme and its scopes`() {
        val doc = spec(getWidget, deleteWidget).openApi()

        val read = (doc / "paths" / "/widgets/{widgetId}" / "get" / "security").arr()
        assertEquals(1, read.size)
        assertEquals(listOf("widgets:read"), (read[0] / "widgets-oauth").strings())

        val write = (doc / "paths" / "/widgets/{widgetId}" / "delete" / "security").arr()
        assertEquals(listOf("widgets:read", "widgets:write"), (write[0] / "widgets-oauth").strings())
    }

    @Test
    fun `the oauth2 scheme lands in components with its flow and scopes`() {
        val doc = spec(getWidget).openApi()
        val scheme = doc / "components" / "securitySchemes" / "widgets-oauth"

        assertEquals("oauth2", (scheme / "type").str())
        val flow = scheme / "flows" / "authorizationCode"
        assertEquals("https://id.example.com/authorize", (flow / "authorizationUrl").str())
        assertEquals("https://id.example.com/token", (flow / "tokenUrl").str())
        assertEquals("https://id.example.com/token", (flow / "refreshUrl").str())
        assertEquals(
            setOf("widgets:read", "widgets:write"),
            (flow / "scopes").keys(),
        )
        assertEquals("Read widgets", (flow / "scopes" / "widgets:read").str())
    }

    @Test
    fun `an api-wide requirement is documented once, and endpoints inherit it`() {
        val doc = spec(getWidget, health, default = listOf(oauth.requires("widgets:read"))).openApi()

        assertEquals(
            listOf("widgets:read"),
            ((doc / "security").arr()[0] / "widgets-oauth").strings(),
        )
        // The endpoint that says nothing says nothing in the document either —
        // that is what inheriting the default looks like.
        assertNull(doc / "paths" / "/health" / "get" / "summary")
        // noSecurity() is an explicit override, and OpenAPI spells it `[]`.
        assertTrue((doc / "paths" / "/health" / "get" / "security").arr().isEmpty())
    }

    @Test
    fun `a scope the scheme never declared fails when the endpoint is built`() {
        val e = assertThrows(IllegalStateException::class.java) {
            endpoint(noInputs) {
                get("widgets")
                security(oauth, "widgets:delete")
                text()
            }
        }
        assertTrue(e.message!!.contains("widgets:delete"), e.message)
        assertTrue(e.message!!.contains("widgets:read"), e.message)
    }

    @Test
    fun `scopes can be declared as bare names`() {
        val plain = oauth2AuthorizationCode(
            authorizationUrl = "https://id.example.com/authorize",
            tokenUrl = "https://id.example.com/token",
            scopes = listOf("widgets:read", "widgets:write"),
        )
        val ep = endpoint(noInputs) {
            get("widgets")
            security(plain, "widgets:write")
            text()
        }

        val flow = spec(ep).openApi() / "components" / "securitySchemes" / "oauth2" /
            "flows" / "authorizationCode"

        // Still one checkbox per scope in the Authorize dialog, in this order.
        assertEquals(listOf("widgets:read", "widgets:write"), (flow / "scopes").keys().toList())
        assertEquals("", (flow / "scopes" / "widgets:read").str())

        // A requirement names scopes either way — that half never carried
        // descriptions.
        val req = (spec(ep).openApi() / "paths" / "/widgets" / "get" / "security").arr()
        assertEquals(listOf("widgets:write"), (req[0] / "oauth2").strings())
    }

    @Test
    fun `a non-oauth scheme takes no scopes`() {
        val bearer = bearerAuth()
        assertThrows(IllegalStateException::class.java) { bearer.requires("anything") }

        val doc = spec(
            endpoint(noInputs) {
                get("widgets")
                security(bearer)
                text()
            },
        ).openApi()
        val scheme = doc / "components" / "securitySchemes" / "bearerAuth"
        assertEquals("http", (scheme / "type").str())
        assertEquals("bearer", (scheme / "scheme").str())
        assertEquals("JWT", (scheme / "bearerFormat").str())
    }

    @Test
    fun `an api key scheme documents where the key travels`() {
        val doc = spec(
            endpoint(noInputs) {
                get("widgets")
                security(apiKeyHeader("X-Api-Key"))
                text()
            },
        ).openApi()
        val scheme = doc / "components" / "securitySchemes" / "apiKey"
        assertEquals("apiKey", (scheme / "type").str())
        assertEquals("header", (scheme / "in").str())
        assertEquals("X-Api-Key", (scheme / "name").str())
    }

    @Test
    fun `two different schemes cannot share one name`() {
        val a = bearerAuth(name = "auth")
        val b = apiKeyHeader("X-Api-Key", name = "auth")
        val one = endpoint(noInputs) { get("a"); security(a); text() }
        val two = endpoint(noInputs) { get("b"); security(b); text() }

        val e = assertThrows(IllegalStateException::class.java) { spec(one, two).openApi() }
        assertTrue(e.message!!.contains("'auth'"), e.message)
    }
}
