package io.github.matthewjones372.pelican.codegen

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.jsonStrings
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType
/**
 * The same descriptions read twice, once into each call shape.
 *
 * What the suspending client has to be is a client: the same class, the same
 * method names, the same parameters and the same declared failures, differing
 * in the keyword on each method and in what the file waits on. The assertions
 * below are that pair of claims — everything the caller writes is unchanged,
 * and nothing of the blocking client's waiting is left behind.
 */
class SuspendingClientTest {

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
    data class Problem(val id: Long)

    private val orderId = pathParam<Long>("orderId")
    private val newOrder = jsonBody<Order>()
    private val noSuchOrder = errorJson<Problem>(404, "No order with that id")

    private val getOrder = endpoint(orderId) {
        get("orders" / orderId)
        operationId = "getOrder"
        json<Order>() orFail noSuchOrder
    }

    private val placeOrder = endpoint(newOrder) {
        post("orders")
        operationId = "placeOrder"
        json<Order>(status = 201)
    }

    private val streamOrders = endpoint {
        get("orders")
        operationId = "streamOrders"
        ndjson<Order>()
    }

    private val cancelOrder = endpoint(orderId) {
        delete("orders" / orderId)
        operationId = "cancelOrder"
        empty(status = 204)
    }

    private val spec = apiSpec(listOf(getOrder, placeOrder, streamOrders, cancelOrder), Schemas) {
        title = "Orders"
        servers = listOf("https://orders.internal")
    }

    private val blocking = spec.kotlinClient("com.example.orders")
    private val suspending = spec.kotlinClient("com.example.orders", callStyle = CallStyle.SUSPENDING)

    @Test
    fun `every method suspends, whatever it answers with`() {
        suspending shouldContain "suspend fun getOrder(orderId: Long): Outcome<GetOrderFailure, Order>"
        suspending shouldContain "suspend fun placeOrder(body: Order): Order"
        suspending shouldContain "suspend fun streamOrders(): Streamed<Order>"
        suspending shouldContain "suspend fun cancelOrder(orderId: Long) {"
    }

    @Test
    fun `and so do the helpers a method reaches the transport through`() {
        suspending shouldContain "private suspend fun exchange(request: ClientRequest): ClientResponse"
        suspending shouldContain "private suspend fun text(request: ClientRequest): TextResponse"
        suspending shouldContain "private suspend fun stream(request: ClientRequest): ClientResponse"
    }

    @Test
    fun `the stage is awaited rather than joined`() {
        suspending shouldContain "transport.send(request).await()"
        suspending shouldNotContain "toCompletableFuture().join()"

        // The wrapper the blocking form unpicks by hand: `await` resumes with
        // the cause, so nothing here has a use for the type — only the comment
        // explaining why, which is why this is the import and not the word.
        suspending shouldNotContain "import java.util.concurrent.CompletionException"
        blocking shouldContain "import java.util.concurrent.CompletionException"
    }

    @Test
    fun `the body is read off the socket somewhere a socket read belongs`() {
        suspending shouldContain "import kotlinx.coroutines.Dispatchers"
        suspending shouldContain "withContext(Dispatchers.IO) { TextResponse(response, response.text()) }"
    }

    @Test
    fun `the header says what the file now needs on its classpath`() {
        suspending shouldContain "org.jetbrains.kotlinx:kotlinx-coroutines-core"
        suspending shouldContain "cancelling the coroutine that made it"
    }

    @Test
    fun `the blocking client is what it was, and knows nothing about coroutines`() {
        blocking shouldContain "fun getOrder(orderId: Long): Outcome<GetOrderFailure, Order>"
        blocking shouldNotContain "suspend"
        blocking shouldNotContain "kotlinx.coroutines"
        blocking shouldContain "transport.send(request).toCompletableFuture().join()"
    }

    @Test
    fun `and the two agree about everything a caller writes`() {
        val declarations = { client: String ->
            client.lines()
                .map { it.trim() }
                .filter { it.startsWith("data class") || it.startsWith("sealed interface") || it.startsWith("enum ") }
        }

        withClue("the payload types and the sealed failures are the descriptions', not the call shape's") {
            declarations(suspending) shouldBe declarations(blocking)
        }

        val signatures = { client: String ->
            client.lines().map { it.trim().removePrefix("suspend ") }.filter { it.startsWith("fun ") }
        }

        withClue("one keyword apart, the methods are the same methods") {
            signatures(suspending) shouldBe signatures(blocking)
        }
    }
}
