package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.ApiErrorEnvelope
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.ProblemDetails
import io.github.matthewjones372.pelican.RefusalRenderer
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.orFail
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The key and the two failures Pelican answers for it are in the document a caller generates from. */
class IdempotencyDocumentTest {

    data class Order(val sku: String)

    data class OutOfStock(val sku: String)

    private val placeOrder = endpoint {
        post("orders")
        operationId = "placeOrder"
        idempotencyKey()
        json<Order>(201)
    }

    private val outOfStock = errorJson<OutOfStock>(409, "Out of stock").tagged("out_of_stock", field = "error")

    private val reserveStock = endpoint {
        post("stock")
        operationId = "reserveStock"
        idempotencyKey(required = false)
        json<Order>(201).orFail(outOfStock)
    }

    private fun document(vararg endpoints: Endpoint<*, *>, refusals: RefusalRenderer = ApiErrorEnvelope): JsonObj =
        apiSpec(endpoints.toList(), RefusalDocumentTest.Schemas) { refusals(refusals) }.openApi()

    private fun operation(document: JsonObj, path: String) = document / "paths" / path / "post"

    @Test
    fun `the key is a header parameter, required where the endpoint requires it`() {
        val required = (operation(document(placeOrder), "/orders") / "parameters").arr().single()
        (required / "name").str() shouldBe "Idempotency-Key"
        (required / "in").str() shouldBe "header"
        (required / "required").bool() shouldBe true
        (required / "schema" / "type").str() shouldBe "string"

        val optional = (operation(document(reserveStock), "/stock") / "parameters").arr().single()
        ((optional / "required")?.bool() ?: false) shouldBe false
    }

    @Test
    fun `the 409 and 422 are refusals in the envelope's own component`() {
        val responses = operation(document(placeOrder), "/orders") / "responses"

        responses.keys().toList() shouldContainExactly listOf("201", "409", "422", "default")
        (responses / "409" / "content" / "application/json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/ApiError"
        ((responses / "409" / "headers" / "Retry-After" / "required")?.bool() ?: false) shouldBe false
        (responses / "422" / "content" / "application/json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/ApiError"
    }

    @Test
    fun `under another envelope they are written in that one`() {
        val conflict = operation(document(placeOrder, refusals = ProblemDetails), "/orders") /
            "responses" / "409" / "content"

        conflict.keys().toList() shouldContainExactly listOf("application/problem+json")
        (conflict / "application/problem+json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/ProblemDetails"
    }

    @Test
    fun `a failure of the endpoint's own under 409 shares it as a tagged oneOf`() {
        val schema = operation(document(reserveStock), "/stock") /
            "responses" / "409" / "content" / "application/json" / "schema"

        (schema / "oneOf").arr().size shouldBe 2
        (schema / "discriminator" / "propertyName").str() shouldBe "error"
        (schema / "discriminator" / "mapping" / "idempotency_key_in_flight").str() shouldBe
            "#/components/schemas/ApiError"
        (schema / "discriminator" / "mapping" / "out_of_stock").str() shouldBe
            "#/components/schemas/OutOfStock"
    }
}
