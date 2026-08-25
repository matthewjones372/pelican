package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * An endpoint answering two ways, published.
 *
 * OpenAPI's `responses` is a map from status to response and always could say
 * this; what could not say it was the endpoint. So the assertions here are
 * about the *interpreter* reading a second success and writing it where the
 * first one goes, with its own schema, its own media type and its own headers.
 *
 * As in [BodiesAndCookiesTest] the schemas are hand-written, so nothing here
 * needs a codec module.
 */
class SeveralResponsesTest {

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
    data class Queued(val ticket: String)
    data class Problem(val code: String)

    private val newOrder = jsonBody<Order>()
    private val requestId = responseHeader<String>("X-Request-Id", "Correlates this answer with the log")
    private val location = responseHeader<String>("Location", "Where the new order lives")

    private val placed = json<Order>(status = 201, location)
    private val queued = json<Queued>(status = 202)
    private val badKey = errorJson<Problem>(401, "Bad API key")

    private val submit = endpoint(newOrder) {
        post("orders")
        operationId = "submitOrder"
        emits(requestId)
        placed or queued orFail badKey
    }

    /** A 202 with no body at all, which is the other half of the common pair. */
    private val accept = endpoint(newOrder) {
        post("intake")
        operationId = "intake"
        json<Order>(status = 200) or empty(status = 202)
    }

    private val document = ApiSpec(listOf(submit, accept), Schemas, title = "Orders").openApi()

    private val submitted = document / "paths" / "/orders" / "post" / "responses"
    private val accepted = document / "paths" / "/intake" / "post" / "responses"

    @Test
    fun `both successes are published, in the order they were declared`() {
        submitted.keys().toList() shouldBe listOf("201", "202", "401")
    }

    @Test
    fun `each success carries its own schema`() {
        (submitted / "201" / "content" / "application/json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/Order"
        (submitted / "202" / "content" / "application/json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/Queued"
    }

    @Test
    fun `a header declared on one response is documented on that response alone`() {
        (submitted / "201" / "headers").keys() shouldBe setOf("X-Request-Id", "Location")
        (submitted / "202" / "headers").keys() shouldBe setOf("X-Request-Id")
    }

    @Test
    fun `a success with no body publishes no content`() {
        (accepted / "202" / "content") shouldBe null
        (accepted / "200" / "content" / "application/json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/Order"
    }

    @Test
    fun `the failures beside them are unchanged`() {
        (submitted / "401" / "description").str() shouldBe "Bad API key"
        (submitted / "401" / "content" / "application/json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/Problem"
    }
}
