package io.github.matthewjones372.pelican.codegen

import io.github.matthewjones372.pelican.*
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * Where a generated method sends its call.
 *
 * This is the reading of `servers` on an operation that has to honour it. The
 * document publishes the URL and a server ignores it — it serves what it
 * serves — but a client is the thing making the call, and a client that sent
 * one to its own base URL because that is where the other methods go would be
 * calling a host the document said does not answer this.
 *
 * Text only, as in [KotlinClientTest]: whether it compiles is asserted in
 * `:example`, whose checked-in client is generated from this code and run
 * against a real server.
 */
class OperationServersClientTest {

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
    data class ImportResult(val id: Long)

    private val upload = rawBody()

    private val listOrders = endpoint(noInputs) {
        get("orders")
        operationId = "listOrders"
        json<Order>()
    }

    private val importOrders = endpoint(upload) {
        post("orders" / "import")
        operationId = "importOrders"
        // Trailing slash on purpose: the client trims its own base the same
        // way, and a doubled slash is a 404 nobody would think to look for.
        servers("https://uploads.example.com/", "https://uploads.eu.example.com")
        json<ImportResult>(status = 201)
    }

    private fun clientFor(vararg endpoints: Endpoint<*, *>, servers: List<String> = emptyList()): String =
        ApiSpec(endpoints.toList(), Schemas, title = "Orders", servers = servers)
            .kotlinClient("com.example.orders")

    private val client = clientFor(listOrders, importOrders, servers = listOf("https://orders.example.com"))

    @Test
    fun `the operation's own server is where its call goes`() {
        client shouldContain """origin = "https://uploads.example.com""""
    }

    @Test
    fun `every other method keeps the client's base url`() {
        client shouldContain """text(request("GET", "/orders"))"""
        client shouldContain """private const val DEFAULT_BASE_URL = "https://orders.example.com""""
    }

    /**
     * The surprise is worth a line: every other method on the class goes where
     * the caller pointed it, and this one does not, whatever they passed.
     */
    @Test
    fun `the method says where it is served`() {
        client shouldContain "Served from https://uploads.example.com, which this operation declares"
    }

    /**
     * The check exists because an empty base builds a relative URI and the
     * JDK's request builder refuses one. Where every operation named a server,
     * nothing would ever read the base, so requiring one would be requiring a
     * URL in order to ignore it.
     */
    @Test
    fun `a spec whose every operation names a server needs no base url at all`() {
        val everywhereElse = clientFor(importOrders)

        everywhereElse shouldNotContain "This client has no base URL"
        everywhereElse shouldContain """private const val DEFAULT_BASE_URL = """""
    }

    @Test
    fun `one operation without a server is enough to need a base url`() {
        clientFor(listOrders, importOrders) shouldContain "This client has no base URL"
    }
}
