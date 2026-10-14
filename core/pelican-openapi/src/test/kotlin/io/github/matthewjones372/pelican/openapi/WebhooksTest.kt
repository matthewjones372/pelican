package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.SecurityRequirement
import io.github.matthewjones372.pelican.Webhook
import io.github.matthewjones372.pelican.apiKeyHeader
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.openapi.div
import io.github.matthewjones372.pelican.requires
import io.github.matthewjones372.pelican.webhook
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

class WebhooksTest {

    object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) {
                components.register(name, jsonObj { "type" to "object" })
            }
            return components.ref(name)
        }
    }

    data class Order(val id: Long)
    data class OrderEvent(val id: Long)

    private val signature = headerParam<String>("X-Signature")
    private val event = jsonBody<OrderEvent>()

    private val subscriberKey = apiKeyHeader("X-Subscriber-Key", name = "subscriberKey")

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

    private val orderAmended = webhook("orderPlaced", method = Method.PUT) {
        body(event)
        empty(status = 204)
    }

    private fun documentOf(
        vararg webhooks: Webhook,
        security: List<SecurityRequirement> = emptyList(),
    ) = apiSpec(listOf(listOrders), Schemas) {
        title = "Orders"
        servers = listOf("https://orders.example.com")
        this.security = security
        this.webhooks = webhooks.toList()
    }.openApi()

    private val document = documentOf(orderPlaced)

    @Test
    fun `a webhook is published under its name, as the operation it describes`() {
        val operation = document / "webhooks" / "orderPlaced" / "post"

        (operation / "operationId").str() shouldBe "orderPlaced"
        (operation / "summary").str() shouldBe "Sent when an order is placed"
        (operation / "responses" / "204" / "description").str() shouldBe "No content."
        (operation / "requestBody" / "content" / "application/json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/OrderEvent"
    }

    /** The declared inputs travel with it, being what a receiver is told to expect. */
    @Test
    fun `the parameters it declares are published like any operation's`() {
        val parameters = (document / "webhooks" / "orderPlaced" / "post" / "parameters").arr()

        parameters.map { (it / "name").str() } shouldContainExactly listOf("X-Signature")
        parameters.map { (it / "in").str() } shouldContainExactly listOf("header")
    }

    /** The whole point of the separation: a webhook is not a route on this service. */
    @Test
    fun `it is nowhere in paths`() {
        (document / "paths").keys() shouldBe setOf("/orders")
    }

    /**
     * OpenAPI keys `webhooks` by name and files the methods of one name
     * together, exactly as it does a path item — so two Pelican webhooks
     * sharing a name are two methods of one entry rather than two entries.
     */
    @Test
    fun `two methods under one name are one entry with two operations`() {
        val both = documentOf(orderPlaced, orderAmended)

        (both / "webhooks").keys() shouldBe setOf("orderPlaced")
        (both / "webhooks" / "orderPlaced").keys() shouldBe setOf("post", "put")
    }

    @Test
    fun `no servers list follows it anywhere`() {
        (document / "webhooks" / "orderPlaced" / "post" / "servers") shouldBe null
        (document / "servers").arr().map { (it / "url").str() } shouldBe listOf("https://orders.example.com")
    }

    @Test
    fun `a webhook that says nothing about security has nothing said for it`() {
        val secured = documentOf(orderPlaced, security = listOf(subscriberKey.requires()))

        (secured / "webhooks" / "orderPlaced" / "post" / "security") shouldBe null
        (secured / "security").arr().size shouldBe 1
    }

    @Test
    fun `a scheme only a webhook requires is still declared`() {
        val signed = webhook("orderPlaced") {
            body(event)
            security(subscriberKey)
            empty(status = 204)
        }
        val published = documentOf(signed)

        (published / "webhooks" / "orderPlaced" / "post" / "security").arr()
            .map { it.keys() } shouldBe listOf(setOf("subscriberKey"))
        (published / "components" / "securitySchemes").keys() shouldBe setOf("subscriberKey")
    }

    /** A document with none of them says nothing rather than saying `webhooks: {}`. */
    @Test
    fun `a document with no webhooks does not mention them`() {
        (documentOf() / "webhooks") shouldBe null
    }
}
