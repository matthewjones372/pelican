package dev.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * A call the service sends rather than answers.
 *
 * The description is an endpoint description, because that is what a webhook is
 * — a method, a body, and what comes back — and the two readings that do
 * anything with it are `pelican-openapi`, which publishes it under `webhooks`,
 * and `pelican-codegen`, which generates the sender. Both have tests of their
 * own; these are about the value, and chiefly about the one thing it must never
 * become, which is a route on this server.
 */
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

    /**
     * The crux of the design, stated as an assertion: there is no path, because
     * the URL belongs to whoever subscribed. What a sender is given at send
     * time is the whole of the destination.
     */
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

    /**
     * A streaming output is declared in terms of a phantom marker whose whole
     * purpose is to type a *handler* that produces the stream. Nothing produces
     * one here, and on the reading end a subscriber streaming back at the
     * service that called it is a shape nothing consumes.
     */
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

    /**
     * The refusal that makes the separation hold.
     *
     * [Webhook.operation] is public because two other modules have to read it,
     * so `handledNow` will accept it and produce a [ServerEndpoint] — and this
     * is where that stops. Bound, it would be served at `/` on this service,
     * which is neither what the description says nor a route anybody wrote.
     * Every interpreter builds its routes from [Api.endpoints], so refusing
     * here refuses on all three at once.
     */
    @Test
    fun `a webhook bound as a handler cannot be served`() {
        val bound = ServerEndpoint(orderPlaced.operation) { CompletableFuture.completedFuture(null) }

        shouldThrow<IllegalArgumentException> {
            Api(endpoints = listOf(bound))
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
        val api = Api(endpoints = emptyList(), webhooks = listOf(orderPlaced))

        api.spec().webhooks shouldBe listOf(orderPlaced)
    }
}
