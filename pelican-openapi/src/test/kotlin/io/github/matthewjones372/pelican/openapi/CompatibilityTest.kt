package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.*
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * The rules, one test each, written as the mistake they are there to catch.
 *
 * A textual diff of two documents reports a line. What a reviewer needs to know
 * is whether that line refuses every request that was working an hour ago — a
 * new required field — or whether it is a new optional one that nobody will
 * notice. These are the cases that decide which.
 *
 * The payload shapes are hand-written, as everywhere else in this module: what
 * is under test is the comparison, not what a codec thinks a Kotlin class looks
 * like.
 */
class CompatibilityTest {

    // ------------------------------------------------------------- the fixture

    data class Order(val id: Long)

    data class CreateOrder(val item: String)

    data class OrderEvent(val id: Long)

    /** A `SchemaSource` whose answers are written by the test, so a field can be added on purpose. */
    class Shapes(private val shapes: Map<String, JsonObj>) : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) {
                components.register(name, shapes[name] ?: jsonObj { "type" to "object" })
            }
            return components.ref(name)
        }
    }

    /** An object schema: each field is a name, whether it is required, and its type. */
    private fun shape(vararg fields: Triple<String, Boolean, JsonValue>) = jsonObj {
        "type" to "object"
        put("properties", jsonObj { fields.forEach { (name, _, type) -> put(name, type) } })
        put("required", jsonStrings(fields.filter { it.second }.map { it.first }))
    }

    private val string = jsonObj { "type" to "string" }
    private val orNull = jsonObj { put("type", jsonArr(listOf(JsonStr("string"), JsonStr("null")))) }

    private val newOrder = jsonBody<CreateOrder>()

    private val getOrder = endpoint {
        get("orders")
        operationId = "getOrder"
        json<Order>()
    }

    private val placeOrder = endpoint(newOrder) {
        post("orders")
        operationId = "placeOrder"
        json<Order>(status = 201)
    }

    private fun spec(
        endpoints: List<Endpoint<*, *>> = listOf(getOrder, placeOrder),
        shapes: Map<String, JsonObj> = emptyMap(),
        security: List<SecurityRequirement> = emptyList(),
        webhooks: List<Webhook> = emptyList(),
    ) = ApiSpec(endpoints, Shapes(shapes), title = "Orders", security = security, webhooks = webhooks)

    private fun changes(before: ApiSpec, after: ApiSpec) = apiChanges(before.openApi(), after.openApi())

    private fun breaking(before: ApiSpec, after: ApiSpec) =
        changes(before, after).filter { it.compatibility == Compatibility.BREAKING }

    private fun onlyBreaking(before: ApiSpec, after: ApiSpec): ApiChange {
        val found = breaking(before, after)
        withClue("expected exactly one breaking change, got $found") { found shouldHaveSize 1 }
        return found.single()
    }

    // -------------------------------------------------------- the same twice

    @Test
    fun `a document compared with itself has nothing to report`() {
        changes(spec(), spec()).shouldBeEmpty()
    }

    // ---------------------------------------------------------- the endpoints

    @Test
    fun `an endpoint that was deleted is a 404 for everyone still calling it`() {
        val gone = onlyBreaking(spec(), spec(endpoints = listOf(placeOrder)))

        gone.where shouldBe "GET /orders"
        gone.what shouldContain "the operation is gone"
    }

    @Test
    fun `an endpoint that was added costs an existing caller nothing`() {
        val added = endpoint {
            get("orders" / "count")
            operationId = "countOrders"
            json<Order>()
        }

        breaking(spec(), spec(endpoints = listOf(getOrder, placeOrder, added))).shouldBeEmpty()
    }

    // --------------------------------------------------------- what is sent

    @Test
    fun `a required field added to a request body refuses every caller that was working`() {
        val before = spec(shapes = mapOf("CreateOrder" to shape(Triple("item", true, string))))
        val after = spec(
            shapes = mapOf(
                "CreateOrder" to shape(Triple("item", true, string), Triple("currency", true, string)),
            ),
        )

        val refused = onlyBreaking(before, after)

        refused.where shouldBe "POST /orders"
        refused.what shouldContain "`currency`"
        refused.what shouldContain "new and required"
    }

    @Test
    fun `the same field added as optional is not a problem for anybody`() {
        val before = spec(shapes = mapOf("CreateOrder" to shape(Triple("item", true, string))))
        val after = spec(
            shapes = mapOf(
                "CreateOrder" to shape(Triple("item", true, string), Triple("currency", false, string)),
            ),
        )

        breaking(before, after).shouldBeEmpty()
    }

    @Test
    fun `a field that became required is the same refusal as a new one`() {
        val before = spec(shapes = mapOf("CreateOrder" to shape(Triple("item", false, string))))
        val after = spec(shapes = mapOf("CreateOrder" to shape(Triple("item", true, string))))

        onlyBreaking(before, after).what shouldContain "required now"
    }

    @Test
    fun `a required query parameter that was not there before refuses them too`() {
        val currency = queryParam<String>("currency")
        val after = endpoint(newOrder, currency) {
            post("orders")
            operationId = "placeOrder"
            json<Order>(status = 201)
        }

        val refused = onlyBreaking(spec(), spec(endpoints = listOf(getOrder, after)))

        refused.what shouldContain "`currency` query"
        refused.what shouldContain "new and required"
    }

    @Test
    fun `an optional query parameter is what a compatible addition looks like`() {
        val currency = queryParam<String>("currency").optional()
        val after = endpoint(newOrder, currency) {
            post("orders")
            operationId = "placeOrder"
            json<Order>(status = 201)
        }

        breaking(spec(), spec(endpoints = listOf(getOrder, after))).shouldBeEmpty()
    }

    @Test
    fun `a request field that may be null now is a loosening, and loosening is safe`() {
        val before = spec(shapes = mapOf("CreateOrder" to shape(Triple("item", true, string))))
        val after = spec(shapes = mapOf("CreateOrder" to shape(Triple("item", true, orNull))))

        breaking(before, after).shouldBeEmpty()
    }

    // ------------------------------------------------------ what is received

    @Test
    fun `a response field that disappeared is a caller reading nothing`() {
        val before = spec(shapes = mapOf("Order" to shape(Triple("id", true, string), Triple("total", true, string))))
        val after = spec(shapes = mapOf("Order" to shape(Triple("id", true, string))))

        val lost = breaking(before, after)

        withClue("both operations answer with an Order, so both are affected") { lost shouldHaveSize 2 }
        lost.first().what shouldContain "`total`"
        lost.first().what shouldContain "is gone"
    }

    @Test
    fun `a response field that may be null now is a promise withdrawn`() {
        val before = spec(shapes = mapOf("Order" to shape(Triple("id", true, string))))
        val after = spec(shapes = mapOf("Order" to shape(Triple("id", true, orNull))))

        breaking(before, after).first().what shouldContain "null"
    }

    @Test
    fun `a response field that is merely new is what a compatible release looks like`() {
        val before = spec(shapes = mapOf("Order" to shape(Triple("id", true, string))))
        val after = spec(shapes = mapOf("Order" to shape(Triple("id", true, string), Triple("total", true, string))))

        breaking(before, after).shouldBeEmpty()
    }

    @Test
    fun `a declared failure that stopped being declared is one a caller handles for nothing`() {
        val declared = errorJson<Order>(404, "No order with that id")
        val before = endpoint {
            get("orders")
            operationId = "getOrder"
            json<Order>() orFail declared
        }

        val stopped = onlyBreaking(
            spec(endpoints = listOf(before, placeOrder)),
            spec(),
        )

        stopped.what shouldContain "404"
        stopped.what shouldContain "no longer declared"
    }

    // ------------------------------------------------------------- the rest

    @Test
    fun `a credential the operation did not ask for is a 401 for everyone not sending it`() {
        val scheme = apiKeyHeader("X-Api-Key", name = "apiKey")
        val secured = endpoint {
            get("orders")
            operationId = "getOrder"
            security(scheme)
            json<Order>()
        }

        onlyBreaking(spec(), spec(endpoints = listOf(secured, placeOrder))).what shouldContain "requires `apiKey` now"
    }

    @Test
    fun `a renamed operationId renames a method in somebody else's source tree`() {
        val renamed = endpoint {
            get("orders")
            operationId = "fetchOrder"
            json<Order>()
        }

        onlyBreaking(spec(), spec(endpoints = listOf(renamed, placeOrder))).what shouldContain "operationId"
    }

    @Test
    fun `a rewritten summary is prose, and prose is not a change to the contract`() {
        val described = endpoint {
            get("orders")
            operationId = "getOrder"
            summary = "Fetch the order"
            json<Order>()
        }

        val reported = changes(spec(), spec(endpoints = listOf(described, placeOrder)))

        reported shouldHaveSize 1
        reported.single().compatibility shouldBe Compatibility.COSMETIC
    }

    @Test
    fun `a webhook is the same rules with the arrows reversed`() {
        val event = jsonBody<OrderEvent>()
        val sent = webhook("orderPlaced") {
            body(event)
            empty(status = 204)
        }

        // The subscriber *reads* what we send, so losing a field breaks them and
        // gaining a required one does not.
        val before = spec(
            shapes = mapOf("OrderEvent" to shape(Triple("id", true, string), Triple("total", true, string))),
            webhooks = listOf(sent),
        )
        val fewer = spec(shapes = mapOf("OrderEvent" to shape(Triple("id", true, string))), webhooks = listOf(sent))
        val more = spec(
            shapes = mapOf(
                "OrderEvent" to shape(
                    Triple("id", true, string),
                    Triple("total", true, string),
                    Triple("tax", true, string),
                ),
            ),
            webhooks = listOf(sent),
        )

        breaking(before, fewer).map { it.where } shouldContainExactly listOf("POST orderPlaced")
        breaking(before, more).shouldBeEmpty()
    }
}
