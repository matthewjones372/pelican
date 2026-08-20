// See OpenApi.kt: the `jsonObj { ... }` builders read as the JSON they emit,
// and ktlint's `wrapping` rule would break them into a staircase.
@file:Suppress("ktlint:standard:wrapping")

package dev.pelican.openapi

import dev.pelican.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.reflect.KType

/**
 * This module depends on pelican-core and nothing else. If any description
 * type ever leaks a Pekko class into its signature, this file stops compiling
 * — which is the layering check.
 */
class DecouplingTest {

    /**
     * A hand-written [SchemaSource], so this module's tests need no codec
     * module either. Any implementation will do — the interpreter only asks for
     * a schema and gets back core's own JSON tree.
     */
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
        assertEquals("3.1.0", (doc / "openapi").str())

        val paths = doc / "paths"
        assertTrue("/widgets/{widgetId}" in paths.keys())
        assertTrue("/widgets" in paths.keys())

        val ok = paths / "/widgets" / "get" / "responses" / "200"
        assertTrue("application/x-ndjson" in (ok / "content").keys())

        assertTrue("Widget" in (doc / "components" / "schemas").keys())
    }

    @Test
    fun `a streamed json array documents as an array of the element schema`() {
        val doc = spec().openApi()
        val schema = doc / "paths" / "/widgets/all" / "get" /
            "responses" / "200" / "content" / "application/json" / "schema"

        assertEquals("array", (schema / "type").str())
        assertEquals("#/components/schemas/Widget", (schema / "items" / "\$ref").str())
    }

    @Test
    fun `the document is core's json tree, not a third-party one`() {
        // Not a formality: if `openApi()` ever returns someone else's tree, this
        // module has acquired a JSON dependency it is not supposed to have.
        val doc: JsonObj = spec().openApi()
        assertTrue(doc.render().startsWith("""{"openapi":"3.1.0","""), doc.render().take(60))
        assertTrue(spec().openApiJson().startsWith("{\n  \"openapi\""))
    }

    @Test
    fun `pekko is genuinely absent from this module's classpath`() {
        val loaded = runCatching { Class.forName("org.apache.pekko.http.javadsl.server.Directives") }
        assertTrue(
            loaded.isFailure,
            "pelican-openapi must not see Pekko; if this passes, a dependency crept in",
        )
    }
}
