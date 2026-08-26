package io.github.matthewjones372.pelican.codegen

import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.apiSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

class SeveralResponsesClientTest {

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
    data class Queued(val id: Long)
    data class Problem(val id: Long)

    private val newOrder = jsonBody<Order>()
    private val location = responseHeader<String>("Location", "Where the new order lives")
    private val badKey = errorJson<Problem>(401, "Bad API key")

    private val submit = endpoint(newOrder) {
        post("orders")
        operationId = "submitOrder"
        json<Order>(status = 201, location) or json<Queued>(status = 202) orFail badKey
    }

    /** Two successes carrying one type, and no failure beside them. */
    private val remember = endpoint(newOrder) {
        put("orders")
        operationId = "rememberOrder"
        json<Order>(status = 200) or json<Order>(status = 201) or empty(status = 204)
    }

    private val client = apiSpec(listOf(submit, remember), Schemas) {
        title = "Orders"
    }.kotlinClient("com.example")

    @Test
    fun `several successes become a sealed type, one member per status`() {
        client shouldContain "sealed interface SubmitOrderResult {"
        client shouldContain "data class Created(val body: Order, val location: String?) : SubmitOrderResult {"
        client shouldContain "data class Accepted(val body: Queued) : SubmitOrderResult {"
    }

    @Test
    fun `the call returns that type, and reads which one arrived off the status`() {
        client shouldContain "fun submitOrder(body: Order): Outcome<SubmitOrderFailure, SubmitOrderResult>"
        client shouldContain "return when (response.status) {"
        client shouldContain "201 -> Outcome.Ok(SubmitOrderResult.Created(" +
            "orderCodec.decoded(response.body, Method.POST, \"/orders\", response.status), " +
            "response.header(\"Location\")))"
        client shouldContain "202 -> Outcome.Ok(SubmitOrderResult.Accepted(queuedCodec.decoded(" +
            "response.body, Method.POST, \"/orders\", response.status)))"
    }

    /** A 2xx the endpoint never declared is a response outside the contract. */
    @Test
    fun `an undeclared success fails the call rather than being read as the nearest one`() {
        client shouldContain "else -> failed(Method.POST, \"/orders\", response)"
    }

    @Test
    fun `with no failures declared the sealed type is the return type itself`() {
        client shouldContain "fun rememberOrder(body: Order): RememberOrderResult"
        client shouldContain "200 -> RememberOrderResult.Ok(" +
            "orderCodec.decoded(response.body, Method.PUT, \"/orders\", response.status))"
        client shouldNotContain "Outcome.Ok(RememberOrderResult"
    }

    /** Two members carrying one payload type, told apart by the status they were declared with. */
    @Test
    fun `two responses carrying one type are two members`() {
        client shouldContain "data class Ok(val body: Order) : RememberOrderResult {"
        client shouldContain "data class Created(val body: Order) : RememberOrderResult {"
    }

    /** Nothing to hold, and Kotlin has no data class without a property. */
    @Test
    fun `a response with neither body nor headers is the one value it will ever be`() {
        client shouldContain "data object NoContent : RememberOrderResult {"
    }
}
