package io.github.matthewjones372.pelican.codegen

import io.github.matthewjones372.pelican.TenantResolver
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.tenantFrom
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** A generated client sends the tenant the way the endpoint finds it: a header, or the host it calls. Spec 0095. */
class TenantClientTest {

    /** The claim resolver reads a caller, so every endpoint here names one; a token is not an argument either. */
    private val caller = authenticated(bearerAuth()) { it.subject }

    private fun clientFor(resolver: TenantResolver): String {
        val tenant = tenantFrom(resolver)
        val orders = endpoint {
            get("orders")
            operationId = "listOrders"
            authenticatedBy(caller)
            tenanted(tenant)
            text()
        }
        return apiSpec(listOf(orders), KotlinClientTest.WidgetSchemas) { title = "Orders" }.kotlinClient("com.example")
    }

    @Test
    fun `a header tenant is a required argument, sent as the header`() {
        val client = clientFor(TenantResolver.header("X-Tenant"))

        client shouldContain "fun listOrders(xTenant: String)"
        client shouldContain """headerParams = listOf("X-Tenant" to xTenant)"""
    }

    @Test
    fun `a subdomain tenant is a required argument, and the host the call goes to`() {
        val client = clientFor(TenantResolver.subdomain(of = "example.com"))

        client shouldContain "fun listOrders(tenant: String)"
        client shouldContain """origin = "https://" + tenant + ".example.com""""
    }

    @Test
    fun `a claim tenant is in the token, so the call takes nothing for it`() {
        val client = clientFor(TenantResolver.claim("tenant"))

        client shouldContain "fun listOrders()"
        client shouldNotContain "tenant: String"
    }
}
