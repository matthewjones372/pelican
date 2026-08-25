package io.github.matthewjones372.pelican.mcp

import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * What a model is told it can call. Derived from the same descriptions the
 * routes and the document come from, so a tool cannot describe a call the
 * service does not answer.
 */
class McpToolsTest {

    data class Order(val id: Long, val item: String)
    data class CreateOrder(val item: String, val quantity: Int = 1)
    data class Queued(val ticket: String)

    private val userId = pathParam<Long>("userId", description = "The user's id")
    private val limit = queryParam("limit", IntCodec.between(1, 100), description = "Maximum rows").default(25)
    private val apiKey = headerParam<String>("X-Api-Key")
    private val newOrder = jsonBody<CreateOrder>(description = "The order to place")

    private val getOrder = endpoint(userId) {
        get("users" / userId / "orders")
        summary = "List orders"
        description = "Every order this user has placed"
        operationId = "listOrders"
        json<Order>()
    }

    private val placeOrder = endpoint(userId, limit, apiKey, newOrder) {
        post("users" / userId / "orders")
        summary = "Place an order"
        operationId = "placeOrder"
        json<Order>(status = 201)
    }

    /** Two right answers to one question, so neither is *the* shape that comes back. */
    private val submitOrder = endpoint(userId, newOrder) {
        post("users" / userId / "orders" / "submit")
        operationId = "submitOrder"
        json<Order>(status = 201) or json<Queued>(status = 202)
    }

    private val secret = endpoint {
        get("secret")
        operationId = "secret"
        hidden = true
        json<Order>()
    }

    // `placeOrder` requires an X-Api-Key, and a required header with nothing
    // behind it is refused: see McpDispatchTest.
    private val options = McpOptions(headers = mapOf("X-Api-Key" to "let-me-in"))

    private fun toolsFor(vararg endpoints: Endpoint<*, *>): List<McpTool> =
        ApiSpec(endpoints.toList(), JacksonCodecs).mcpTools(options)

    @Test
    fun `one tool per endpoint, named as the document and the generated client name it`() {
        toolsFor(getOrder, placeOrder, submitOrder).map { it.name } shouldContainExactly
            listOf("listOrders", "placeOrder", "submitOrder")
    }

    @Test
    fun `a hidden endpoint is no more a tool than it is an operation`() {
        toolsFor(getOrder, secret).map { it.name } shouldContainExactly listOf("listOrders")
    }

    @Test
    fun `the description is the long one where there is one, and the summary is the title`() {
        val listed = toolsFor(getOrder).single()
        listed.description shouldBe "Every order this user has placed"
        listed.title shouldBe "List orders"

        // Nothing to tell apart: the summary is the only prose there is, so it
        // is the description and a title repeating it says nothing.
        val placed = toolsFor(placeOrder).single()
        placed.description shouldBe "Place an order"
        placed.title.shouldBeNull()
    }

    @Test
    fun `a refinement the server enforces reaches the schema a model reads`() {
        val schema = toolsFor(placeOrder).single().inputSchema
        val limitSchema = schema.property("limit")

        limitSchema["minimum"] shouldBe JsonNum(1)
        limitSchema["maximum"] shouldBe JsonNum(100)
        withClue("a defaulted parameter is one the model may leave out") {
            schema.required() shouldContainExactly listOf("userId", "body")
        }
    }

    @Test
    fun `a path parameter carries its type and what it is for`() {
        val schema = toolsFor(getOrder).single().inputSchema.property("userId")

        schema["type"] shouldBe JsonStr("integer")
        schema["format"] shouldBe JsonStr("int64")
        schema["description"] shouldBe JsonStr("The user's id")
    }

    @Test
    fun `a header is not a tool argument, and a credential is not a thing to invent`() {
        val schema = toolsFor(placeOrder).single().inputSchema
        withClue("a model asked for an X-Api-Key would make one up") {
            (schema["properties"] as JsonObj)["X-Api-Key"].shouldBeNull()
        }
    }

    @Test
    fun `a body is one argument, with the types it names beside it`() {
        val schema = toolsFor(placeOrder).single().inputSchema
        val body = schema.property("body")

        (body["\$ref"] as JsonStr).value shouldBe "#/\$defs/CreateOrder"
        withClue("the pointer has to resolve inside the schema the model was handed") {
            ((schema["\$defs"] as? JsonObj)?.get("CreateOrder")).shouldNotBeNull()
        }
    }

    @Test
    fun `an output schema is published only where one JSON answer declares its type`() {
        val placed = toolsFor(placeOrder).single().outputSchema
        withClue("structuredContent becomes binding once a tool publishes an outputSchema") {
            placed.shouldNotBeNull()
        }
        (placed!!["\$ref"] as JsonStr).value shouldBe "#/\$defs/Order"

        withClue("two declared successes are two shapes, and there is one outputSchema to publish") {
            toolsFor(submitOrder).single().outputSchema.shouldBeNull()
        }
    }
}

private fun JsonObj.property(name: String): JsonObj = (this["properties"] as JsonObj)[name] as JsonObj

private fun JsonObj.required(): List<String> =
    ((this["required"] as? JsonArr)?.items.orEmpty()).map { (it as JsonStr).value }
