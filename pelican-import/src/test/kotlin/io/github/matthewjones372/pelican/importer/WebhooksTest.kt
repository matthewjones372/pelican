package io.github.matthewjones372.pelican.importer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

class WebhooksTest {

    private fun withWebhooks(webhooks: String, paths: String? = null): String = imported(
        buildString {
            appendLine("openapi: 3.1.0")
            appendLine("""info: { title: Orders, version: "1.0.0" }""")
            if (paths == null) {
                appendLine("paths: {}")
            } else {
                appendLine("paths:")
                appendLine(paths.trimIndent().prependIndent("  "))
            }
            appendLine("webhooks:")
            append(webhooks.trimIndent().prependIndent("  "))
        },
    )

    private val generated = withWebhooks(
        """
        orderPlaced:
          post:
            operationId: orderPlaced
            summary: Sent when an order is placed
            requestBody:
              required: true
              content:
                application/json:
                  schema:
                    type: object
                    properties:
                      id: { type: integer, format: int64 }
            parameters:
              - { name: X-Signature, in: header, required: true, schema: { type: string } }
            responses:
              "204": { description: Accepted by the subscriber }
        """,
    )

    /**
     * The name is the identity: OpenAPI keys `webhooks` by it, and it is what
     * the sender generated for this is called. There is no path to write down,
     * so `webhook(...)` takes the name and the method and nothing else.
     */
    @Test
    fun `a webhook is described by name, with no route at all`() {
        val source = withWebhooks(
            """
            orderPlaced:
              post:
                operationId: orderPlaced
                responses:
                  "204": { description: ok }
            """,
        )

        source shouldContain """val orderPlaced = webhook("orderPlaced") {"""
        // No route call of any kind: there is nothing for one to name.
        source shouldNotContain "post("
    }

    /** Its inputs are declared in the block, since nothing binds a handler to one. */
    @Test
    fun `the body and headers it carries come with it`() {
        generated shouldContain "body(orderPlacedBody)"
        generated shouldContain "header(xSignature)"
        generated shouldContain "empty()"
    }

    @Test
    fun `webhooks land in their own list, and not among the endpoints`() {
        generated shouldContain "val testWebhooks: List<Webhook> = listOf(\n    orderPlaced,\n)"
        generated shouldContain "val testEndpoints: List<Endpoint<*, *>> = listOf(\n)"
        generated shouldContain "webhooks = testWebhooks"
    }

    /** A method other than POST is said, because `webhook(...)` assumes the common one. */
    @Test
    fun `a webhook on another method says which`() {
        val source = withWebhooks(
            """
            orderUpdated:
              put:
                operationId: orderUpdated
                responses:
                  "204": { description: ok }
            """,
        )

        source shouldContain """webhook("orderUpdated", method = Method.PUT) {"""
    }

    @Test
    fun `two methods under one name are two descriptions of that name`() {
        val source = withWebhooks(
            """
            orderChanged:
              post:
                operationId: orderCreated
                responses:
                  "204": { description: ok }
              put:
                operationId: orderAmended
                responses:
                  "204": { description: ok }
            """,
        )

        source shouldContain """val orderCreated = webhook("orderChanged") {"""
        source shouldContain """val orderAmended = webhook("orderChanged", method = Method.PUT) {"""
    }

    @Test
    fun `an operationId shared with a route is still a clash`() {
        shouldThrow<ImportFailure> {
            withWebhooks(
                """
                orderPlaced:
                  post:
                    operationId: getOrder
                    responses:
                      "204": { description: ok }
                """,
                paths = """
                    /orders:
                      get:
                        operationId: getOrder
                        responses:
                          "200": { description: ok }
                """,
            )
        }.message.orEmpty() shouldContain "share the operationId getOrder"
    }

    /** And a webhook with none is named in the same failure a route with none is. */
    @Test
    fun `a webhook with no operationId is named as one`() {
        shouldThrow<ImportFailure> {
            withWebhooks(
                """
                orderPlaced:
                  post:
                    responses:
                      "204": { description: ok }
                """,
            )
        }.message.orEmpty() shouldContain "webhook orderPlaced (POST)"
    }

    /**
     * `servers` under a webhook has no reading: the destination is a URL a
     * subscriber registered out of band, and OpenAPI says nothing about what a
     * Server Object would mean beside it. Refused rather than dropped, which is
     * what this importer does with everything it cannot describe.
     */
    @Test
    fun `a webhook that names a server is refused rather than quietly ignored`() {
        shouldThrow<ImportFailure> {
            withWebhooks(
                """
                orderPlaced:
                  post:
                    operationId: orderPlaced
                    servers:
                      - { url: https://subscriber.example.com }
                    responses:
                      "204": { description: ok }
                """,
            )
        }.message.orEmpty() shouldContain "sent to the URL a subscriber registered"
    }

    /** A path parameter on something with no path. */
    @Test
    fun `a path parameter on a webhook says why there is nowhere for it to go`() {
        shouldThrow<ImportFailure> {
            withWebhooks(
                """
                orderPlaced:
                  post:
                    operationId: orderPlaced
                    parameters:
                      - { name: id, in: path, required: true, schema: { type: string } }
                    responses:
                      "204": { description: ok }
                """,
            )
        }.message.orEmpty() shouldContain "a webhook has no path"
    }

    @Test
    fun `a streamed response from a subscriber is refused`() {
        shouldThrow<ImportFailure> {
            withWebhooks(
                """
                orderPlaced:
                  post:
                    operationId: orderPlaced
                    responses:
                      "200":
                        description: ok
                        content:
                          application/x-ndjson:
                            schema: { type: object }
                """,
            )
        }.message.orEmpty() shouldContain "nothing here consumes a stream from a subscriber"
    }

    /** The same release valve as everywhere else, addressed by operationId. */
    @Test
    fun `a webhook can be excluded by name like any other operation`() {
        val source = imported(
            """
            openapi: 3.1.0
            info: { title: Orders, version: "1.0.0" }
            paths:
              /orders:
                get:
                  operationId: listOrders
                  responses:
                    "200": { description: ok }
            webhooks:
              orderPlaced:
                post:
                  operationId: orderPlaced
                  responses:
                    "204": { description: ok }
            """,
            importOptions("test", "test") { exclude = setOf("orderPlaced") },
        )

        source shouldNotContain "webhook("
        source shouldNotContain "Webhook"
    }
}
