package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.ErrorOutput
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.ResponseHeader
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.SecurityRequirement
import io.github.matthewjones372.pelican.Webhook
import io.github.matthewjones372.pelican.apiKeyHeader
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.formBody
import io.github.matthewjones372.pelican.jsonArr
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.jsonStrings
import io.github.matthewjones372.pelican.openapi.div
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.or
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.requires
import io.github.matthewjones372.pelican.responseHeader
import io.github.matthewjones372.pelican.webhook
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType
import io.kotest.matchers.collections.shouldContain as shouldInclude

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
    ) = apiSpec(endpoints, Shapes(shapes)) {
        title = "Orders"
        this.security = security
        this.webhooks = webhooks
    }

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

    // ------------------------------------------- the branches nothing reached
    //
    // Each of these is a rule the classifier already had and nothing exercised,
    // which is the shape of mistake that matters here: an unreached branch does
    // not fail loudly, it files a breaking change as compatible and a reviewer
    // reads the summary rather than the diff.

    @Test
    fun `a response that stopped being sent is a caller matching on a status it will never see`() {
        val two = endpoint {
            get("orders")
            operationId = "getOrder"
            json<Order>() or empty(202)
        }

        onlyBreaking(spec(listOf(two, placeOrder)), spec()).what shouldContain "202"
    }

    @Test
    fun `a new response beside the old one costs nobody anything`() {
        val two = endpoint {
            get("orders")
            operationId = "getOrder"
            json<Order>() or empty(202)
        }

        breaking(spec(), spec(listOf(two, placeOrder))).shouldBeEmpty()
    }

    @Test
    fun `a response header that is gone is one a caller was reading`() {
        val header = responseHeader<String>("X-Cursor")
        val paged = endpoint {
            get("orders")
            operationId = "getOrder"
            emits(header)
            json<Order>()
        }

        onlyBreaking(spec(listOf(paged, placeOrder)), spec()).what shouldContain "X-Cursor"
    }

    @Test
    fun `a response header that became optional is a promise withdrawn`() {
        val always = responseHeader<String>("X-Cursor")
        val sometimes = responseHeader<String>("X-Cursor").optional()

        fun withHeader(h: ResponseHeader<*>) = endpoint {
            get("orders")
            operationId = "getOrder"
            emits(h)
            json<Order>()
        }

        onlyBreaking(spec(listOf(withHeader(always), placeOrder)), spec(listOf(withHeader(sometimes), placeOrder)))
            .what shouldContain "X-Cursor"
    }

    @Test
    fun `a request body that is no longer read cannot refuse anybody`() {
        val without = endpoint {
            post("orders")
            operationId = "placeOrder"
            json<Order>(status = 201)
        }

        breaking(spec(), spec(listOf(getOrder, without))).shouldBeEmpty()
    }

    @Test
    fun `a media type dropped from a response is one a caller was asking for`() {
        val negotiated = endpoint(jsonBody<CreateOrder>() or formBody<CreateOrder>()) {
            post("orders")
            operationId = "placeOrder"
            json<Order>(status = 201)
        }

        onlyBreaking(spec(listOf(getOrder, negotiated)), spec()).what shouldContain "form-urlencoded"
    }

    @Test
    fun `a media type added to a request is one more way to call it`() {
        val negotiated = endpoint(jsonBody<CreateOrder>() or formBody<CreateOrder>()) {
            post("orders")
            operationId = "placeOrder"
            json<Order>(status = 201)
        }

        breaking(spec(), spec(listOf(getOrder, negotiated))).shouldBeEmpty()
    }

    @Test
    fun `a pattern a request field did not have to match before refuses what used to be sent`() {
        val loose = mapOf("CreateOrder" to shape(Triple("item", true, string)))
        val strict = mapOf(
            "CreateOrder" to shape(
                Triple("item", true, jsonObj { "type" to "string"; "pattern" to "^[a-z]+$" }),
            ),
        )

        onlyBreaking(spec(shapes = loose), spec(shapes = strict)).what shouldContain "must match `^[a-z]+$` now"
    }

    @Test
    fun `an operation marked deprecated still answers, so nobody is broken yet`() {
        val going = endpoint {
            get("orders")
            operationId = "getOrder"
            deprecated = true
            json<Order>()
        }

        val found = changes(spec(), spec(listOf(going, placeOrder)))
        breaking(spec(), spec(listOf(going, placeOrder))).shouldBeEmpty()
        found.map { it.what }.joinToString() shouldContain "deprecated"
    }

    @Test
    fun `a requirement dropped is one fewer thing a caller has to send`() {
        val scheme = bearerAuth()
        breaking(spec(security = listOf(scheme.requires())), spec()).shouldBeEmpty()
    }

    @Test
    fun `prose never reads as a change to the contract`() {
        val described = endpoint {
            get("orders")
            operationId = "getOrder"
            summary = "Fetch the orders"
            description = "Every order this caller may see"
            json<Order>()
        }

        changes(spec(), spec(listOf(described, placeOrder)))
            .forEach { it.compatibility shouldBe Compatibility.COSMETIC }
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

    // ------------------------------------------------- one shape becomes several

    data class Unavailable(val id: Long, val message: String)

    data class RegistryDown(val id: Long, val message: String)

    data class NotRecorded(val id: Long, val message: String)

    private val problem = shape(Triple("id", true, string), Triple("message", true, string))

    private fun adopt(vararg failures: ErrorOutput<*>) = endpoint {
        post("adoptions")
        operationId = "adopt"
        json<Order>().orFail(*failures)
    }

    private val unavailable = errorJson<Unavailable>(503, "Try again")
    private val registryDown = errorJson<RegistryDown>(503, "The registry is down").tagged("registry_down")
    private val notRecorded = errorJson<NotRecorded>(503, "The sale was not recorded").tagged("not_recorded")

    private fun adoptions(endpoint: Endpoint<*, *>, shapes: Map<String, JsonObj>) =
        spec(endpoints = listOf(endpoint), shapes = shapes)

    private val oneFailure = adoptions(adopt(unavailable), mapOf("Unavailable" to problem))

    @Test
    fun `one failure split into two tagged ones that both keep every field breaks nobody`() {
        val split = adoptions(
            adopt(registryDown, notRecorded),
            mapOf("RegistryDown" to problem, "NotRecorded" to problem),
        )

        val found = changes(oneFailure, split)

        breaking(oneFailure, split).shouldBeEmpty()
        found.map { it.what } shouldInclude
            "the 503 response (application/json) has 2 shapes now: `registry_down`, `not_recorded`"
    }

    @Test
    fun `a field dropped from one of the new shapes is a break, named with that shape`() {
        val split = adoptions(
            adopt(registryDown, notRecorded),
            mapOf("RegistryDown" to problem, "NotRecorded" to shape(Triple("message", true, string))),
        )

        val lost = onlyBreaking(oneFailure, split)

        lost.what shouldContain "`id`"
        lost.what shouldContain "`not_recorded`"
        lost.what shouldContain "is gone"
    }

    @Test
    fun `two shapes joined into one break a caller handling whichever lost a field`() {
        val split = adoptions(
            adopt(registryDown, notRecorded),
            mapOf(
                "RegistryDown" to problem,
                "NotRecorded" to shape(
                    Triple("id", true, string),
                    Triple("message", true, string),
                    Triple("retry", true, string),
                ),
            ),
        )

        // Each shape also loses the tag its schema declares (spec 0066), which a caller branching on it read.
        breaking(split, oneFailure).map { it.what } shouldContainExactly listOf(
            "`kind` in the 503 response (application/json) as `registry_down` is gone",
            "`kind` in the 503 response (application/json) as `not_recorded` is gone",
            "`retry` in the 503 response (application/json) as `not_recorded` is gone",
        )
    }

    @Test
    fun `a request that became several shapes breaks only when none of them takes the old payload`() {
        val item = Triple("item", true, string)
        val currency = Triple("currency", true, string)
        val before = spec(shapes = mapOf("CreateOrder" to shape(item)))
        fun either(vararg shapes: JsonObj) =
            spec(shapes = mapOf("CreateOrder" to jsonObj { put("oneOf", jsonArr(shapes.toList())) }))

        breaking(before, either(shape(item), shape(item, currency))).shouldBeEmpty()

        val refused = breaking(before, either(shape(item, currency), shape(currency)))
        withClue("expected the new required field in each shape, got $refused") { refused shouldHaveSize 3 }
        refused.map { it.what }.forEach { it shouldContain "shape" }
    }

    // ------------------------------------------- several shapes on both sides

    data class ShopClosed(val id: Long, val message: String)

    private val shopClosed = errorJson<ShopClosed>(503, "The shop is closed").tagged("shop_closed")

    private val twoFailures = adoptions(
        adopt(registryDown, notRecorded),
        mapOf("RegistryDown" to problem, "NotRecorded" to problem),
    )

    @Test
    fun `a tag added to a response is one a client switching on the old ones has never heard of`() {
        val three = adoptions(
            adopt(registryDown, notRecorded, shopClosed),
            mapOf("RegistryDown" to problem, "NotRecorded" to problem, "ShopClosed" to problem),
        )

        val surprise = onlyBreaking(twoFailures, three)

        surprise.what shouldContain "a new shape, `shop_closed`"
        breaking(three, twoFailures).shouldBeEmpty()
    }

    @Test
    fun `a field dropped from a shape both sides have is named with that shape`() {
        val thinner = adoptions(
            adopt(registryDown, notRecorded),
            mapOf("RegistryDown" to problem, "NotRecorded" to shape(Triple("id", true, string))),
        )

        val lost = onlyBreaking(twoFailures, thinner)

        lost.what shouldContain "`message`"
        lost.what shouldContain "`not_recorded`"
    }

    @Test
    fun `a renamed tag field leaves a caller nothing to branch on`() {
        val reason = adoptions(
            adopt(
                errorJson<RegistryDown>(503, "The registry is down").tagged("registry_down", field = "reason"),
                errorJson<NotRecorded>(503, "The sale was not recorded").tagged("not_recorded", field = "reason"),
            ),
            mapOf("RegistryDown" to problem, "NotRecorded" to problem),
        )

        breaking(twoFailures, reason).map { it.what } shouldInclude
            "the 503 response (application/json) is told apart by `reason` where it was `kind`"
    }

    @Test
    fun `a shape dropped from a request refuses whoever was sending it, and a new one refuses nobody`() {
        val item = shape(Triple("item", true, string))
        val gift = shape(Triple("item", true, string), Triple("to", true, string))
        val bulk = shape(Triple("items", true, string))
        fun any(vararg shapes: JsonObj) =
            spec(shapes = mapOf("CreateOrder" to jsonObj { put("anyOf", jsonArr(shapes.toList())) }))

        onlyBreaking(any(item, gift), any(item)).what shouldContain "no longer accepts shape 2"
        breaking(any(item, gift), any(item, gift, bulk)).shouldBeEmpty()
    }

    // ------------------------------------------- holding the spec, not a file

    @Test
    fun `a spec compared with a published document reports what the two-document form does`() {
        val published = spec(shapes = mapOf("CreateOrder" to shape(Triple("item", true, string))))
        val proposed = spec(
            shapes = mapOf(
                "CreateOrder" to shape(Triple("item", true, string), Triple("currency", true, string)),
            ),
        )

        proposed.changesFrom(published.openApi()) shouldContainExactly
            apiChanges(published.openApi(), proposed.openApi())
    }

    @Test
    fun `the receiver is the proposed side, so a field it dropped reads as the loss it is`() {
        val published = spec(
            endpoints = listOf(getOrder),
            shapes = mapOf("Order" to shape(Triple("id", true, string), Triple("nickname", true, string))),
        )
        val proposed = spec(endpoints = listOf(getOrder), shapes = mapOf("Order" to shape(Triple("id", true, string))))

        val lost = proposed.changesFrom(published.openApi()).filter { it.compatibility == Compatibility.BREAKING }

        // Compared the other way round this is a *new* response field, which
        // breaks nobody — so the direction is the whole content of this test.
        withClue("expected the dropped field to be breaking, got $lost") { lost shouldHaveSize 1 }
        lost.single().what shouldContain "`nickname`"
        lost.single().what shouldContain "is gone"
    }
}
