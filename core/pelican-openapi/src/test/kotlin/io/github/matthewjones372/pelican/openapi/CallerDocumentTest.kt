package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.openIdConnect
import io.github.matthewjones372.pelican.optional
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The requirement comes from the caller's declaration, so the document cannot disagree with what is enforced. */
class CallerDocumentTest {

    private val oidc = openIdConnect("https://id.home.arpa/.well-known/openid-configuration", name = "pocket-id")
    private val caller = authenticated(oidc, Identity::subject)

    private val mine = endpoint {
        get("me")
        authenticatedBy(caller)
        text()
    }

    private val greeting = endpoint {
        get("hello")
        authenticatedBy(caller.optional())
        text()
    }

    private val health = endpoint {
        get("health")
        text()
    }

    private val doc = apiSpec(listOf(mine, greeting, health), SecurityTest.Schemas).openApi()

    @Test
    fun `an endpoint taking a caller requires its scheme, and the scheme is described once`() {
        val required = (doc / "paths" / "/me" / "get" / "security").arr()
        required.size shouldBe 1
        (required[0] / "pocket-id").strings() shouldBe emptyList()

        val scheme = doc / "components" / "securitySchemes" / "pocket-id"
        (scheme / "type").str() shouldBe "openIdConnect"
        (scheme / "openIdConnectUrl").str() shouldBe "https://id.home.arpa/.well-known/openid-configuration"
    }

    @Test
    fun `an optional caller is anyone, or someone signed in`() {
        val either = (doc / "paths" / "/hello" / "get" / "security").arr()
        either.size shouldBe 2
        (either[0] as JsonObj).fields.isEmpty() shouldBe true
        (either[1] / "pocket-id").strings() shouldBe emptyList()
    }

    @Test
    fun `an endpoint without one requires nothing`() {
        (doc / "paths" / "/health" / "get").keys().contains("security") shouldBe false
    }
}
