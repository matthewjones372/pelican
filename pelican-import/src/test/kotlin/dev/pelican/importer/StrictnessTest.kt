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

    /**
     * The refusal this used to be is the one thing on the list that stopped
     * being true, so the test that pinned it now pins the opposite: two 2xx
     * read, both declared, and a handler that names the one it is producing.
     */
    @Test
    fun `two successful responses become two declared responses`() {
        val source = imported(
            document(
                """
                /orders:
                  post:
                    operationId: placeOrder
                    responses:
                      "200": { description: Placed }
                      "202": { description: Queued }
                """,
            ),
        )
        source shouldContain "empty(status = 200) or empty(status = 202)"
    }

    /**
     * A stream is produced in the server library's own type, which is the half
     * of "several responses" core cannot reach — so this one is still refused,
     * and says which way out is available.
     */
    @Test
    fun `a streamed response beside another 2xx is a response nothing could produce`() {
        val message = refusing(
            """
            /orders:
              get:
                operationId: listOrders
                responses:
                  "200":
                    description: A stream
                    content:
                      application/x-ndjson: { schema: { type: object, properties: { id: { type: integer } } } }
                  "202": { description: Queued }
            """,
        )
        message shouldContain "listOrders (GET /orders)"
        message shouldContain "The 200 response streams"
        message shouldContain "Document the stream as the only 2xx"
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

    /*
     * A list of scalars is describable, and is asserted in `ImportTest`. What
     * follows is the rest of what an array parameter can say, each refused for
     * a reason of its own rather than for being an array.
     */

    @Test
    fun `a deepObject parameter is an object spread out, not a list`() {
        refusing(listParameter("filter", "query", "style: deepObject", "explode: true")) shouldContain
            "encoded as 'deepObject'"
    }

    @Test
    fun `a list of objects is refused, because an element still decodes from one string`() {
        refusing(
            """
            /orders:
              get:
                operationId: listOrders
                parameters:
                  - name: filters
                    in: query
                    schema:
                      type: array
                      items: { type: object, properties: { name: { type: string } } }
                responses:
                  "204": { description: ok }
            """,
        ) shouldContain "An element of parameter 'filters' is an object"
    }

    @Test
    fun `an exploded header list would need a header field name per value`() {
        refusing(listParameter("X-Tags", "header", "explode: true")) shouldContain
            "a header field has one name"
    }

    @Test
    fun `a comma-joined cookie list is a cookie value RFC 6265 excludes`() {
        refusing(listParameter("tags", "cookie", "explode: false")) shouldContain "RFC 6265 excludes the comma"
    }

    @Test
    fun `a list in the path has no segment to be`() {
        refusing(
            """
            /orders/{ids}:
              get:
                operationId: getOrders
                parameters:
                  - name: ids
                    in: path
                    required: true
                    schema: { type: array, items: { type: string } }
                responses:
                  "204": { description: ok }
            """,
        ) shouldContain "it is in the path"
    }

    @Test
    fun `a constraint on the list itself is one nothing would enforce`() {
        val message = refusing(
            """
            /orders:
              get:
                operationId: listOrders
                parameters:
                  - name: tags
                    in: query
                    schema: { type: array, minItems: 1, items: { type: string } }
                responses:
                  "204": { description: ok }
            """,
        )
        message shouldContain "constrains the list itself with minItems"
        message shouldContain "put the constraint on `items`"
    }

    @Test
    fun `a style on a parameter with no parts says something it cannot mean`() {
        refusing(
            """
            /orders/{id}:
              get:
                operationId: getOrder
                parameters:
                  - name: id
                    in: path
                    required: true
                    style: label
                    schema: { type: string }
                responses:
                  "204": { description: ok }
            """,
        ) shouldContain "its schema says it has none"
    }

    /** One array parameter, with whatever [serialisation] the case is about. */
    private fun listParameter(name: String, location: String, vararg serialisation: String): String =
        """
        /orders:
          get:
            operationId: listOrders
            parameters:
              - name: $name
                in: $location
                ${serialisation.joinToString("\n                ")}
                schema: { type: array, items: { type: string } }
            responses:
              "204": { description: ok }
        """

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
                  default: { description: anything else }
            /b:
              get:
                operationId: b
                responses:
                  "200": { description: ok }
                  default: { description: anything else }
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
                  default: { description: anything else }
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
