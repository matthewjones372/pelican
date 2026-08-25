package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class WebhooksTest {

    data class OrderEvent(val id: Long)
    data class Ack(val received: Boolean)

    private val signature = headerParam<String>("X-Signature")
    private val event = jsonBody<OrderEvent>()

    private val orderPlaced = webhook("orderPlaced") {
        body(event)
        header(signature)
        summary = "Sent when an order is placed"
        empty(status = 204)
    }

    @Test
    fun `a webhook is named, not routed`() {
        orderPlaced.name shouldBe "orderPlaced"
        orderPlaced.operation.method shouldBe Method.POST
        orderPlaced.operation.webhookName shouldBe "orderPlaced"
        orderPlaced.toString() shouldBe "webhook orderPlaced (POST)"
    }

    @Test
    fun `it has no path at all`() {
        orderPlaced.operation.pathSpec.segments.shouldBeEmpty()
    }

    @Test
    fun `the method is an argument, since there is no route to write it on`() {
        val amended = webhook("orderAmended", method = Method.PUT) {
            body(event)
            empty(status = 204)
        }

        amended.operation.method shouldBe Method.PUT
    }

    /** Its inputs and its response are read the same way any endpoint's are. */
    @Test
    fun `what it carries and what comes back are ordinary descriptions`() {
        orderPlaced.operation.bodyInput shouldBe event
        orderPlaced.operation.headerParams shouldBe listOf(signature)
        orderPlaced.operation.output.status shouldBe 204
    }

    @Test
    fun `a webhook that writes a path is refused where it is written`() {
        shouldThrow<IllegalStateException> {
            webhook("orderPlaced") {
                post("hooks" / "orders")
                empty(status = 204)
            }
        }.message.orEmpty() shouldContain "a webhook has none"
    }

    /** A server list here would name a host the subscriber owns. */
    @Test
    fun `a webhook that names a server is refused too`() {
        shouldThrow<IllegalStateException> {
            webhook("orderPlaced") {
                servers("https://subscriber.example.com")
                empty(status = 204)
            }
        }.message.orEmpty() shouldContain "the host it reaches is the subscriber's"
    }

    @Test
    fun `a webhook cannot answer with a stream`() {
        shouldThrow<IllegalStateException> {
            webhook("orderPlaced") {
                body(event)
                ndjson<OrderEvent>()
            }
        }.message.orEmpty() shouldContain "Nothing here consumes a stream from a subscriber"
    }

    @Test
    fun `a webhook without a name could not be filed anywhere`() {
        shouldThrow<IllegalArgumentException> {
            webhook(" ") { empty(status = 204) }
        }.message.orEmpty() shouldContain "OpenAPI keys `webhooks` by it"
    }

    // ------------------------------------------------------ and never a route

    @Test
    fun `a webhook bound as a handler cannot be served`() {
        val bound = ServerEndpoint(orderPlaced.operation) { CompletableFuture.completedFuture(null) }

        shouldThrow<IllegalArgumentException> {
            api(endpoints = listOf(bound))
        }.message.orEmpty() shouldContain "a call this service sends rather than one it answers"
    }

    /** And the same on the description half, where it would be published as a path. */
    @Test
    fun `a webhook listed among the described endpoints is refused as well`() {
        shouldThrow<IllegalArgumentException> {
            ApiSpec(endpoints = listOf(orderPlaced.operation), schemas = NoCodecs)
        }.message.orEmpty() shouldContain "Pass them as `webhooks = listOf(...)`"
    }

    /** Two of one name and method would be one entry in the document, and the second would win. */
    @Test
    fun `two webhooks with the same name and method are refused`() {
        val again = webhook("orderPlaced") {
            body(event)
            json<Ack>()
        }

        shouldThrow<IllegalArgumentException> {
            ApiSpec(emptyList(), NoCodecs, webhooks = listOf(orderPlaced, again))
        }.message.orEmpty() shouldContain "one entry per name and method"
    }

    /** Several methods under one name are what a Path Item Object already is. */
    @Test
    fun `two methods under one name are allowed`() {
        val amended = webhook("orderPlaced", method = Method.PUT) {
            body(event)
            empty(status = 204)
        }

        ApiSpec(emptyList(), NoCodecs, webhooks = listOf(orderPlaced, amended)).webhooks.size shouldBe 2
    }

    /** An `Api` hands its webhooks to the description half, since that is what publishes them. */
    @Test
    fun `the spec an api describes carries them`() {
        val api = api(endpoints = emptyList()) {
            webhooks = listOf(orderPlaced)
        }

        api.spec().webhooks shouldBe listOf(orderPlaced)
    }
}
