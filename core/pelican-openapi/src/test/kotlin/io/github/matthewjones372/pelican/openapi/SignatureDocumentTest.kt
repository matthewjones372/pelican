package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.stripeSignature
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** What a signed endpoint publishes: the headers the signature arrives in, and the 400 it is refused with. */
class SignatureDocumentTest {

    private val stripe = stripeSignature { listOf("whsec_test") }

    private val hook = endpoint(jsonBody<String>()) {
        post("stripe")
        signedBy(stripe)
        text()
    }

    private fun operation(): JsonObj =
        (apiSpec(listOf(hook), SecurityTest.Schemas).openApi() / "paths" / "/stripe" / "post") as JsonObj

    @Test
    fun `the signature header is a required header parameter, saying what it signs`() {
        val parameter = (operation() / "parameters").arr().single()

        (parameter / "name").str() shouldBe "Stripe-Signature"
        (parameter / "in").str() shouldBe "header"
        (parameter / "required").bool() shouldBe true
        (parameter / "description").str() shouldBe stripe.description
    }

    @Test
    fun `the 400 is in the refusal envelope`() {
        (operation() / "responses" / "400" / "content" / "application/json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/ApiError"
    }

    @Test
    fun `no security scheme is invented for it`() {
        operation().keys().contains("security") shouldBe false
        (operation() / "parameters").arr().map { (it / "name").str() } shouldContainExactly listOf("Stripe-Signature")
    }
}
