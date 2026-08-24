package dev.pelican.importer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * What the import refuses, and what it says.
 *
 * The messages are asserted as well as the refusals, because a strict import
 * whose failure does not say which operation and which keyword is a strict
 * import nobody can act on. Every one of these names the operation, the place
 * in the document, and the way out.
 */
class StrictnessTest {

    private fun refusing(paths: String): String =
        shouldThrow<ImportFailure> { imported(document(paths)) }.message.orEmpty()

    @Test
    fun `two successful responses are two answers to a question with one`() {
        val message = refusing(
            """
            /orders:
              post:
                operationId: placeOrder
                responses:
                  "200": { description: Placed }
                  "202": { description: Queued }
            """,
        )
        message shouldContain "placeOrder (POST /orders)"
        message shouldContain "Two successful responses are documented (200, 202)"
    }

    @Test
    fun `a default response says "and anything else", which an endpoint cannot`() {
        refusing(
            """
            /orders:
              get:
                operationId: listOrders
                responses:
                  "200": { description: ok }
                  default: { description: Anything else }
            """,
        ) shouldContain "`default` response"
    }

    @Test
    fun `two media types for one body are two decodes of one request`() {
        refusing(
            """
            /orders:
              post:
                operationId: placeOrder
                requestBody:
                  content:
                    application/json: { schema: { type: object } }
                    application/xml: { schema: { type: object } }
                responses:
                  "204": { description: ok }
            """,
        ) shouldContain "application/json, application/xml"
    }

    @Test
    fun `a union is refused rather than generated as Any`() {
        val message = shouldThrow<ImportFailure> {
            imported(
                """
                openapi: 3.1.0
                info: { title: T, version: "1" }
                components:
                  schemas:
                    Payment:
                      oneOf:
                        - { type: object, properties: { card: { type: string } } }
                        - { type: object, properties: { iban: { type: string } } }
                paths:
                  /payments:
                    post:
                      operationId: pay
                      requestBody:
                        content:
                          application/json:
                            schema: { ${'$'}ref: '#/components/schemas/Payment' }
                      responses:
                        "204": { description: ok }
                """,
            )
        }.message.orEmpty()
        message shouldContain "one of several shapes"
    }

    @Test
    fun `a list parameter is refused, because an input decodes one value`() {
        refusing(
            """
            /orders:
              get:
                operationId: listOrders
                parameters:
                  - name: tags
                    in: query
                    schema: { type: array, items: { type: string } }
                responses:
                  "204": { description: ok }
            """,
        ) shouldContain "Parameter 'tags' is a list"
    }

    @Test
    fun `an operation with no operationId has nothing to name the generated value after`() {
        val message = shouldThrow<ImportFailure> {
            imported(
                document(
                    """
                    /orders:
                      get:
                        responses:
                          "204": { description: ok }
                    """,
                ),
            )
        }.message.orEmpty()
        message shouldContain "GET /orders"
        message shouldContain "operationId"
    }

    @Test
    fun `every operation that cannot be described is reported, not just the first`() {
        val message = refusing(
            """
            /a:
              get:
                operationId: a
                responses:
                  "200": { description: ok }
                  "201": { description: also ok }
            /b:
              get:
                operationId: b
                responses:
                  "200": { description: ok }
                  "202": { description: also ok }
            """,
        )
        message shouldContain "2 operations cannot be described"
        message shouldContain "a (GET /a)"
        message shouldContain "b (GET /b)"
        // And the way out is the line the reader is going to want next.
        message shouldContain """exclude("a", "b")"""
    }

    @Test
    fun `an excluded operation is left out, and takes its problems with it`() {
        val paths = """
            /a:
              get:
                operationId: a
                responses:
                  "200": { description: ok }
                  "201": { description: also ok }
            /b:
              get:
                operationId: b
                responses:
                  "200":
                    description: ok
                    content:
                      application/json: { schema: { type: object, properties: { id: { type: string } } } }
        """
        val generated = imported(document(paths), ImportOptions("app", "test", exclude = setOf("a")))
        generated shouldContain "val b = endpoint(noInputs)"
        generated shouldNotContain "val a ="
    }

    @Test
    fun `a schema only an excluded operation reached is not generated either`() {
        val generated = imported(
            """
            openapi: 3.1.0
            info: { title: T, version: "1" }
            components:
              schemas:
                Kept: { type: object, properties: { id: { type: string } } }
                Dropped:
                  oneOf:
                    - { type: object }
                    - { type: string }
            paths:
              /a:
                get:
                  operationId: a
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json: { schema: { ${'$'}ref: '#/components/schemas/Dropped' } }
              /b:
                get:
                  operationId: b
                  responses:
                    "200":
                      description: ok
                      content:
                        application/json: { schema: { ${'$'}ref: '#/components/schemas/Kept' } }
            """,
            ImportOptions("app", "test", exclude = setOf("a")),
        )
        generated shouldContain "data class Kept("
        generated shouldNotContain "Dropped"
    }

    @Test
    fun `webhooks describe calls the service makes, which is the other direction again`() {
        shouldThrow<ImportFailure> {
            imported(
                """
                openapi: 3.1.0
                info: { title: T, version: "1" }
                webhooks:
                  orderPlaced:
                    post:
                      operationId: orderPlaced
                      responses:
                        "204": { description: ok }
                paths: {}
                """,
            )
        }.message.orEmpty() shouldContain "webhooks"
    }
}
