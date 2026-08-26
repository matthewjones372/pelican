package io.github.matthewjones372.pelican.codegen

import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.Webhook
import io.github.matthewjones372.pelican.apiKeyHeader
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.jsonStrings
import io.github.matthewjones372.pelican.requires
import io.github.matthewjones372.pelican.webhook
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType
class WebhookSenderTest {

    object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) {
                components.register(
                    name,
                    jsonObj {
                        "type" to "object"
                        put("properties", jsonObj { put("id", jsonObj { "type" to "integer" }) })
                        put("required", jsonStrings(listOf("id")))
                    },
                )
            }
            return components.ref(name)
        }
    }

    data class Order(val id: Long)
    data class OrderPlaced(val id: Long)
    data class Rejected(val id: Long)

    private val signature = headerParam<String>("X-Signature")
    private val event = jsonBody<OrderPlaced>()

    private val listOrders = endpoint {
        get("orders")
        operationId = "listOrders"
        json<Order>()
    }

    private val orderPlaced = webhook("orderPlaced") {
        body(event)
        header(signature)
        summary = "Sent when an order is placed"
        empty(status = 204)
    }

    private fun clientFor(
        vararg webhooks: Webhook,
        endpoints: List<Endpoint<*, *>> = listOf(listOrders),
    ): String = apiSpec(endpoints, Schemas) {
        title = "Orders"
        servers = listOf("https://orders.example.com")
        this.webhooks = webhooks.toList()
    }.kotlinClient("com.example.orders")

    private val client = clientFor(orderPlaced)

    @Test
    fun `the sender takes the subscriber's url and sends the call there`() {
        client shouldContain "fun orderPlaced(url: String, body: OrderPlaced, xSignature: String)"
        client shouldContain "origin = url"
    }

    /** Appending anything to the URL a subscriber gave would invent a route on their host. */
    @Test
    fun `nothing is appended to the url it was given`() {
        client shouldContain """request(Method.POST, "", origin = url"""
    }

    @Test
    fun `the client's standing headers do not go to a subscriber`() {
        client shouldContain "standingHeaders = emptyMap()"
        client shouldContain "A subscriber is not the API"
    }

    /** Declared inputs are carried, so a receiver that asked for a signature gets one. */
    @Test
    fun `a header the webhook declares travels with it`() {
        client shouldContain """headerParams = listOf("X-Signature" to xSignature)"""
    }

    @Test
    fun `what comes back is what the receiver answered`() {
        val withPayload = clientFor(
            webhook("orderRejected") {
                body(event)
                json<Rejected>()
            },
        )

        withPayload shouldContain "fun orderRejected(url: String, body: OrderPlaced): Rejected {"
        // A webhook has no path of its own, so a refusal names where it went.
        withPayload shouldContain "return rejectedCodec.decoded(response.body, Method.POST, url, response.status)"
        withPayload shouldContain "The response below is the one the *receiver* sends"
    }

    /**
     * The document's own requirement is what a caller presents to *this* API,
     * and a webhook is presented to a subscriber. OpenAPI does not say the two
     * are the same, so a webhook that declared nothing has nothing said for it
     * — where an endpoint that declared nothing inherits.
     */
    @Test
    fun `a webhook does not inherit the document's security requirement`() {
        val key = apiKeyHeader("X-Api-Key", name = "apiKey")
        val secured = apiSpec(listOf(listOrders), Schemas) {
            title = "Orders"
            servers = listOf("https://orders.example.com")
            security = listOf(key.requires())
            webhooks = listOf(orderPlaced)
        }.kotlinClient("com.example.orders")

        // A method's KDoc is what precedes it, so each is read up to its own
        // signature: the endpoint's inherits, and the sender's says nothing.
        secured.substringBefore("fun listOrders(") shouldContain "Requires: apiKey"
        secured.substringAfter("fun listOrders(").substringBefore("fun orderPlaced(") shouldNotContain "Requires:"
    }

    /** A failed send names where it went, there being no path to name instead. */
    @Test
    fun `a send that failed reports the url rather than a path`() {
        client shouldContain """failed(Method.POST, url, response)"""
    }

    @Test
    fun `a spec that is only webhooks needs no base url`() {
        val sendersOnly = apiSpec(emptyList(), Schemas) {
            title = "Orders"
            webhooks = listOf(orderPlaced)
        }
            .kotlinClient("com.example.orders")

        sendersOnly shouldNotContain "This client has no base URL"
        sendersOnly shouldContain "fun orderPlaced(url: String"
    }
}
