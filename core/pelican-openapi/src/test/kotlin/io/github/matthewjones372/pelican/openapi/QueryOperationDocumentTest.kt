package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.SchemaComponents
import io.github.matthewjones372.pelican.SchemaSource
import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.contentLocation
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.jsonObj
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

class QueryOperationDocumentTest {

    object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) components.register(name, jsonObj { "type" to "object" })
            return components.ref(name)
        }
    }

    data class Filter(val status: String)

    data class Order(val id: Long)

    private val search = endpoint(jsonBody<Filter>()) {
        query("orders" / "search")
        operationId = "searchOrders"
        emits(contentLocation)
        json<Order>()
    }

    private val get = endpoint {
        get("orders" / "latest")
        json<Order>()
    }

    private fun doc(version: OpenApiVersion) =
        apiSpec(listOf(search, get), Schemas) { title = "Orders" }.openApi(version)

    @Test
    fun `OpenAPI 3_2 writes the operation under query, with its request body`() {
        val item = doc(OpenApiVersion.V3_2_0) / "paths" / "/orders/search"

        item.keys() shouldBe setOf("query")
        val operation = item / "query"
        (operation / "operationId").str() shouldBe "searchOrders"
        (operation / "requestBody" / "content").keys() shouldContain "application/json"
        (operation / "responses" / "200" / "headers").keys() shouldContain "Content-Location"
    }

    @Test
    fun `OpenAPI 3_1 has no query field, so the operation is written under x-query`() {
        val item = doc(OpenApiVersion.V3_1_0) / "paths" / "/orders/search"

        item.keys() shouldBe setOf("x-query")
        (item / "x-query" / "operationId").str() shouldBe "searchOrders"
    }

    @Test
    fun `no document carries a query key that its version does not define`() {
        (doc(OpenApiVersion.V3_1_0) / "paths" / "/orders/search").keys() shouldNotContain "query"
    }

    @Test
    fun `existing methods keep their lower case key in both versions`() {
        (doc(OpenApiVersion.V3_1_0) / "paths" / "/orders/latest").keys() shouldBe setOf("get")
        (doc(OpenApiVersion.V3_2_0) / "paths" / "/orders/latest").keys() shouldBe setOf("get")
    }
}
