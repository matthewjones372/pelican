package io.github.matthewjones372.pelican.importer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Reading a document written against OpenAPI 3.2.
 *
 * The importer already accepted any `openapi` version it was handed, because
 * what it reads is the objects rather than the number at the top. What 3.2
 * changed is the objects: a streamed response puts its frame under
 * `itemSchema`, an event stream's item is the parsed event rather than the
 * payload, and a cookie says `style: "cookie"`. Pelican's own emitter writes
 * all three, and the example round-trips its own document, so this is not a
 * hypothetical document from somewhere else.
 */
class OpenApi32Test {

    private fun document32(paths: String, components: String? = null): String =
        listOfNotNull(
            "openapi: 3.2.0",
            "info:",
            "  title: Test",
            "  version: \"1.0.0\"",
            components?.let { "components:\n  schemas:\n" + it.trimIndent().prependIndent("    ") },
            "paths:",
            paths.trimIndent().prependIndent("  "),
        ).joinToString("\n")

    private val order = """
        Order:
          type: object
          properties:
            id:
              type: integer
          required: [id]
    """

    @Test
    fun `an NDJSON response reads its frame from itemSchema`() {
        val source = imported(
            document32(
                """
                /orders:
                  get:
                    operationId: streamOrders
                    responses:
                      "200":
                        description: A stream
                        content:
                          application/x-ndjson:
                            itemSchema:
                              ${'$'}ref: '#/components/schemas/Order'
                """,
                order,
            ),
        )

        source shouldContain "ndjson<Order>()"
    }

    @Test
    fun `an event stream reads its payload out of the event's data field`() {
        val source = imported(
            document32(
                """
                /orders/watch:
                  get:
                    operationId: watchOrders
                    responses:
                      "200":
                        description: An event stream
                        content:
                          text/event-stream:
                            itemSchema:
                              type: object
                              properties:
                                event:
                                  type: string
                                  const: order
                                data:
                                  type: string
                                  contentMediaType: application/json
                                  contentSchema:
                                    ${'$'}ref: '#/components/schemas/Order'
                              required: [event, data]
                """,
                order,
            ),
        )

        source shouldContain "sse<Order>()"
    }

    @Test
    fun `an event stream whose data says nothing about its contents is refused`() {
        val failure = shouldThrow<ImportFailure> {
            imported(
                document32(
                    """
                    /orders/watch:
                      get:
                        operationId: watchOrders
                        responses:
                          "200":
                            description: An event stream
                            content:
                              text/event-stream:
                                itemSchema:
                                  type: object
                                  properties:
                                    data:
                                      type: string
                                  required: [data]
                    """,
                ),
            )
        }

        failure.message.orEmpty() shouldContain "does not say what is inside it"
    }

    @Test
    fun `a cookie written as style cookie reads as the same list a form one did`() {
        val source = imported(
            document32(
                """
                /orders:
                  get:
                    operationId: listOrders
                    parameters:
                      - name: seen
                        in: cookie
                        required: false
                        style: cookie
                        schema:
                          type: array
                          items:
                            type: string
                      - name: session
                        in: cookie
                        required: true
                        style: cookie
                        schema:
                          type: string
                    responses:
                      "200":
                        description: Orders
                        content:
                          application/json:
                            schema:
                              ${'$'}ref: '#/components/schemas/Order'
                """,
                order,
            ),
        )

        // `repeated()` is what `style: cookie` with its default `explode: true`
        // means, and a scalar cookie carries no style at all on this side.
        source shouldContain "cookieParam<String>(\"seen\""
        source shouldContain ".repeated()"
        source shouldContain "cookieParam<String>(\"session\""
    }

    @Test
    fun `a cookie joined by commas is still refused, whichever style names it`() {
        val failure = shouldThrow<ImportFailure> {
            imported(
                document32(
                    """
                    /orders:
                      get:
                        operationId: listOrders
                        parameters:
                          - name: seen
                            in: cookie
                            required: false
                            style: cookie
                            explode: false
                            schema:
                              type: array
                              items:
                                type: string
                        responses:
                          "204":
                            description: Nothing
                    """,
                ),
            )
        }

        failure.message.orEmpty() shouldContain "RFC 6265 excludes the comma"
    }

    @Test
    fun `a 3_1 document still reads exactly as it did`() {
        // The two spellings are read, and neither displaced the other.
        val source = imported(
            document(
                """
                /orders:
                  get:
                    operationId: streamOrders
                    parameters:
                      - name: seen
                        in: cookie
                        required: false
                        explode: true
                        schema:
                          type: array
                          items:
                            type: string
                    responses:
                      "200":
                        description: A stream
                        content:
                          application/x-ndjson:
                            schema:
                              ${'$'}ref: '#/components/schemas/Order'
                """,
                order,
            ),
        )

        source shouldContain "ndjson<Order>()"
        source shouldContain ".repeated()"
    }

    @Test
    fun `the number at the top is not what the importer switches on`() {
        // A document may say 3.2 and use nothing 3.2 added, or say 3.1 and be
        // read the same way. Both are true here, and this pins that neither
        // spelling of the version is required to unlock the other's fields.
        val paths = """
            /orders:
              get:
                operationId: streamOrders
                responses:
                  "200":
                    description: A stream
                    content:
                      application/x-ndjson:
                        schema:
                          ${'$'}ref: '#/components/schemas/Order'
        """

        imported(document32(paths, order)) shouldBe imported(document(paths, order))
    }
}
