package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.TenantResolver
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.tenantFrom
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** What a tenanted endpoint publishes: the header or the host to send to, and the 400, 403 and 404. Spec 0095. */
class TenantDocumentTest {

    private val caller = authenticated(bearerAuth()) { it.subject }

    private fun operation(resolver: TenantResolver): JsonObj {
        val tenant = tenantFrom(resolver)
        val orders = endpoint {
            get("orders")
            authenticatedBy(caller)
            tenanted(tenant)
            text()
        }
        return (apiSpec(listOf(orders), SecurityTest.Schemas).openApi() / "paths" / "/orders" / "get") as JsonObj
    }

    @Test
    fun `a header tenant is a required header parameter`() {
        val parameter = (operation(TenantResolver.header("X-Tenant")) / "parameters").arr().single()

        (parameter / "name").str() shouldBe "X-Tenant"
        (parameter / "in").str() shouldBe "header"
        (parameter / "required").bool() shouldBe true
    }

    @Test
    fun `a subdomain tenant is a server variable on the operation`() {
        val server = (operation(TenantResolver.subdomain(of = "example.com")) / "servers").arr().single()

        (server / "url").str() shouldBe "https://{tenant}.example.com"
        (server / "variables" / "tenant" / "default").str() shouldBe "tenant"
    }

    @Test
    fun `a claim tenant adds nothing a caller sends`() {
        val op = operation(TenantResolver.claim("tenant"))

        op.keys().contains("parameters") shouldBe false
        op.keys().contains("servers") shouldBe false
    }

    @Test
    fun `the 400, 403 and 404 are in the refusal envelope`() {
        val responses = operation(TenantResolver.header("X-Tenant")) / "responses"

        listOf("400", "403", "404").forEach { status ->
            (responses / status / "content" / "application/json" / "schema" / "\$ref").str() shouldBe
                "#/components/schemas/ApiError"
        }
        (responses / "403" / "description").str() shouldBe "The caller belongs to another tenant."
    }
}
