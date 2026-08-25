// See OpenApi.kt: the `jsonObj { ... }` builders read as the JSON they emit,
// and ktlint's `wrapping` rule would break them into a staircase.
@file:Suppress("ktlint:standard:wrapping")

package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * This module depends on pelican-core and nothing else. If any description
 * type ever leaks a Pekko class into its signature, this file stops compiling
 * — which is the layering check.
 */
class DecouplingTest {

    object WidgetSchemas : SchemaSource {
        override fun schema(type: KType, components: SchemaComponents): JsonObj {
            val name = (type.classifier as KClass<*>).simpleName!!
            if (!components.isRegistered(name)) {
                components.register(name, jsonObj {
                    "type" to "object"
                    put("properties", jsonObj {
                        put("id", jsonObj { "type" to "integer" })
                        put("name", jsonObj { "type" to "string" })
                    })
                    put("required", jsonStrings(listOf("id", "name")))
                })
            }
            return components.ref(name)
        }
    }

    data class Widget(val id: Long, val name: String, val tags: List<String>)

    private val widgetId = pathParam<Long>("widgetId")
    private val page = queryParam<Int>("page").default(1)

    private val getWidget = endpoint(widgetId) {
        get("widgets" / widgetId)
        summary = "Fetch a widget"
        json<Widget>()
    }

    private val streamWidgets = endpoint(page) {
        get("widgets")
        summary = "Stream widgets"
        ndjson<Widget>()
    }

    private val listWidgets = endpoint(page) {
        get("widgets" / "all")
        summary = "List widgets as a streamed array"
        jsonArray<Widget>()
    }

    private fun spec() = ApiSpec(
        endpoints = listOf(getWidget, streamWidgets, listWidgets),
        schemas = WidgetSchemas,
        title = "Widgets",
        version = "2.0.0",
    )

    @Test
    fun `a spec can be generated with no server library present`() {
        val doc = spec().openApi()
        (doc / "openapi").str() shouldBe "3.1.0"

        val paths = doc / "paths"
        paths.keys() shouldContain "/widgets/{widgetId}"
        paths.keys() shouldContain "/widgets"

        val ok = paths / "/widgets" / "get" / "responses" / "200"
        (ok / "content").keys() shouldContain "application/x-ndjson"

        (doc / "components" / "schemas").keys() shouldContain "Widget"
    }

    @Test
    fun `a streamed json array documents as an array of the element schema`() {
        val doc = spec().openApi()
        val schema = doc / "paths" / "/widgets/all" / "get" /
            "responses" / "200" / "content" / "application/json" / "schema"

        (schema / "type").str() shouldBe "array"
        (schema / "items" / "\$ref").str() shouldBe "#/components/schemas/Widget"
    }

    @Test
    fun `the document is core's json tree, not a third-party one`() {
        // Not a formality: if `openApi()` ever returns someone else's tree, this
        // module has acquired a JSON dependency it is not supposed to have.
        val doc: JsonObj = spec().openApi()
        withClue(doc.render().take(60)) { doc.render() shouldStartWith """{"openapi":"3.1.0",""" }
        spec().openApiJson() shouldStartWith "{\n  \"openapi\""
    }

    @Test
    fun `pekko is genuinely absent from this module's classpath`() {
        withClue("pelican-openapi must not see Pekko; if this passes, a dependency crept in") {
            shouldThrow<ClassNotFoundException> { Class.forName("org.apache.pekko.http.javadsl.server.Directives") }
        }
    }
}
