package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.anyOf
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.oauth2ClientCredentials
import io.github.matthewjones372.pelican.permission
import io.github.matthewjones372.pelican.role
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** What `requires` publishes: the 403, the permissions a scheme can name as scopes, and the rest in words. */
class RequiresDocumentTest {

    private val company = oauth2ClientCredentials(
        tokenUrl = "https://id.example.com/token",
        scopes = mapOf("orders:refund" to "Refund an order"),
        name = "company",
    )

    private val refund = endpoint {
        post("refunds")
        description = "Refunds an order in full."
        authenticatedBy(authenticated(company) { it.subject })
        requires(role("support"), permission("orders:refund"), permission("orders:audit"))
        text()
    }

    private val audit = endpoint {
        get("audit")
        authenticatedBy(authenticated(bearerAuth()) { it.subject })
        requires(anyOf(role("admin"), permission("orders:refund")))
        text()
    }

    private val doc = apiSpec(listOf(refund, audit), SecurityTest.Schemas).openApi()

    @Test
    fun `the 403 is documented in the refusal envelope`() {
        val forbidden = doc / "paths" / "/refunds" / "post" / "responses" / "403"

        (forbidden / "description").str() shouldBe
            "The caller does not have role `support` and permission `orders:refund` and permission `orders:audit`."
        (forbidden / "content" / "application/json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/ApiError"
    }

    @Test
    fun `a permission the scheme declares as a scope is required as one, and one it does not is left to the words`() {
        val requirement = (doc / "paths" / "/refunds" / "post" / "security").arr().single()

        (requirement / "company").strings() shouldBe listOf("orders:refund")
    }

    @Test
    fun `the description says what OpenAPI has no field for, after what the author wrote`() {
        (doc / "paths" / "/refunds" / "post" / "description").str() shouldBe
            "Refunds an order in full.\n\n" +
            "Requires role `support` and permission `orders:refund` and permission `orders:audit`."
        (doc / "paths" / "/audit" / "get" / "description").str() shouldBe
            "Requires (role `admin` or permission `orders:refund`)."
    }

    @Test
    fun `either of an anyOf is not a scope every caller needs, so none is required`() {
        val requirement = (doc / "paths" / "/audit" / "get" / "security").arr().single()

        (requirement / "bearerAuth").strings() shouldBe emptyList()
    }
}
