package dev.pelican.openapi

import dev.pelican.*
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * `default`, published.
 *
 * OpenAPI's `responses` is a map whose keys are statuses and one word: the
 * response that stands for the statuses the other keys did not enumerate. It is
 * the only entry in that map no handler can produce, which is why it is written
 * from `ep.errors` under a key rather than from an output — and why the
 * assertions here are about the *document*, there being nothing on the wire to
 * assert about.
 *
 * As in [SeveralResponsesTest] the schemas are hand-written, so nothing here
 * needs a codec module.
 */
class DefaultResponseTest {

    object Schemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) {
                components.register(name, jsonObj { "type" to "object" })
            }
            return components.ref(name)
        }
    }

    data class Widget(val id: Long)
    data class Problem(val code: String)

    private val widgetId = pathParam<Long>("widgetId")
    private val retryAfter = responseHeader<Long>("Retry-After", "Seconds to wait")
    private val missing = errorJson<Problem>(404, "No widget with that id")

    private val getWidget = endpoint(widgetId) {
        get("widgets" / widgetId)
        operationId = "getWidget"
        defaultJson<Problem>("Any other failure", retryAfter)
        json<Widget>() orFail missing
    }

    private val listWidgets = endpoint(noInputs) {
        get("widgets")
        operationId = "listWidgets"
        defaultResponse("Any other failure")
        json<Widget>()
    }

    private val document = ApiSpec(listOf(getWidget, listWidgets), Schemas, title = "Widgets").openApi()

    private val one = document / "paths" / "/widgets/{widgetId}" / "get" / "responses"
    private val all = document / "paths" / "/widgets" / "get" / "responses"

    /**
     * As a set: the entries are written in the order they were declared, and
     * `default` was declared in the block while the 404 arrived with the
     * output. That is an order, not a meaning — OpenAPI's `responses` is a map.
     */
    @Test
    fun `it is published under default, beside the statuses that were named`() {
        one.keys() shouldBe setOf("200", "404", "default")
    }

    @Test
    fun `it carries the schema it was declared with`() {
        (one / "default" / "content" / "application/json" / "schema" / "\$ref").str() shouldBe
            "#/components/schemas/Problem"
        (one / "default" / "description").str() shouldBe "Any other failure"
    }

    /** Declared on that response, like a `Retry-After` on a 429 and for the same reason. */
    @Test
    fun `the headers it declares are published on it`() {
        (one / "default" / "headers" / "Retry-After" / "description").str() shouldBe "Seconds to wait"
    }

    @Test
    fun `one with no payload is a description and nothing else`() {
        all.keys() shouldBe setOf("200", "default")
        (all / "default" / "description").str() shouldBe "Any other failure"
        (all / "default" / "content") shouldBe null
    }
}
