package dev.pelican.http4k.docs

import dev.pelican.Api
import dev.pelican.Cors
import dev.pelican.cors
import dev.pelican.div
import dev.pelican.endpoint
import dev.pelican.http4k.handledNow
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.pathParam
import org.http4k.core.Method
import org.http4k.core.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

data class Widget(val id: Long, val name: String)

private val widgetId = pathParam<Long>("widgetId")

private val getWidget = endpoint(widgetId) {
    get("widgets" / widgetId)
    operationId = "getWidget"
    json<Widget>()
}

private fun api(cors: Cors? = null) = Api(
    endpoints = listOf(getWidget handledNow { id -> Widget(id, "widget-$id") }),
    codecs = JacksonCodecs,
    title = "Widgets",
    cors = cors,
)

class DocsTest {

    @Test
    fun `the document and the page are served alongside the endpoints`() {
        val handler = api().handlerWithDocs(Docs(docsPath = "/api-docs"))

        val spec = handler(Request(Method.GET, "/openapi.json"))
        assertEquals(200, spec.status.code)
        assertEquals("application/json", spec.header("Content-Type"))
        assertTrue("\"operationId\": \"getWidget\"" in spec.bodyString(), spec.bodyString().take(300))

        val page = handler(Request(Method.GET, "/api-docs"))
        assertEquals(200, page.status.code)
        assertTrue(page.header("Content-Type")!!.startsWith("text/html"))
        assertTrue("swagger-ui" in page.bodyString())

        // The endpoints are still there; the docs are an addition, not a wrapper.
        assertEquals("""{"id":3,"name":"widget-3"}""", handler(Request(Method.GET, "/widgets/3")).bodyString())
    }

    @Test
    fun `each page can be switched off on its own`() {
        val specOnly = api().handlerWithDocs(Docs(docsPath = null))
        assertEquals(200, specOnly(Request(Method.GET, "/openapi.json")).status.code)
        assertEquals(404, specOnly(Request(Method.GET, "/docs")).status.code)

        val pageOnly = api().handlerWithDocs(Docs(openApiPath = null))
        assertEquals(404, pageOnly(Request(Method.GET, "/openapi.json")).status.code)
        // With no spec endpoint the page embeds the document rather than fetching it.
        assertTrue("\"openapi\"" in pageOnly(Request(Method.GET, "/docs")).bodyString())
    }

    /**
     * A browser tool reading the spec is the same request as a browser calling
     * an endpoint, so the API's own `cors` covers both. Without this a
     * cross-origin Swagger UI or client generator gets nothing to read.
     */
    @Test
    fun `the document carries the API's cross-origin headers`() {
        val handler = api(cors("https://tools.example.com")).handlerWithDocs()

        val spec = handler(
            Request(Method.GET, "/openapi.json").header("Origin", "https://tools.example.com"),
        )
        assertEquals("https://tools.example.com", spec.header("Access-Control-Allow-Origin"))

        // And nothing changes for an API that never asked for CORS.
        val plain = api().handlerWithDocs()(
            Request(Method.GET, "/openapi.json").header("Origin", "https://tools.example.com"),
        )
        assertEquals(null, plain.header("Access-Control-Allow-Origin"))
    }

    @Test
    fun `with both switched off the handler is the endpoints alone`() {
        val handler = api().handlerWithDocs(Docs(openApiPath = null, docsPath = null))
        assertEquals(404, handler(Request(Method.GET, "/docs")).status.code)
        assertEquals(200, handler(Request(Method.GET, "/widgets/1")).status.code)
    }
}
