package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.openapi.div
import io.github.matthewjones372.pelican.rawBody
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * An operation served from somewhere other than the rest of the document.
 *
 * OpenAPI puts `servers` at three levels and reads the innermost one that is
 * there; Pelican describes two of them, the document's and the operation's, and
 * this is the second. The Server Object is written by the same function either
 * way, so the assertion worth making is that the two lists sit where they
 * belong and do not disturb each other.
 */
class OperationServersTest {

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
    data class ImportResult(val rows: Int)

    private val upload = rawBody()

    private val listOrders = endpoint {
        get("orders")
        operationId = "listOrders"
        json<Order>()
    }

    private val importOrders = endpoint(upload) {
        post("orders" / "import")
        operationId = "importOrders"
        servers("https://uploads.example.com", "https://uploads.eu.example.com")
        json<ImportResult>(status = 201)
    }

    private val document = apiSpec(listOf(listOrders, importOrders), Schemas) {
        title = "Orders"
        servers = listOf("https://orders.example.com")
    }.openApi()

    @Test
    fun `the operation carries its own servers list`() {
        val servers = document / "paths" / "/orders/import" / "post" / "servers"

        servers.arr().map { (it / "url").str() } shouldBe
            listOf("https://uploads.example.com", "https://uploads.eu.example.com")
    }

    @Test
    fun `an operation that declared none says nothing, and inherits the document's`() {
        (document / "paths" / "/orders" / "get" / "servers") shouldBe null
        (document / "servers").arr().map { (it / "url").str() } shouldBe listOf("https://orders.example.com")
    }

    /** Same shape at both levels, because it is the same object in OpenAPI. */
    @Test
    fun `a server is written the same way wherever it sits`() {
        (document / "paths" / "/orders/import" / "post" / "servers").arr().first().keys() shouldBe setOf("url")
        (document / "servers").arr().first().keys() shouldBe setOf("url")
    }
}
