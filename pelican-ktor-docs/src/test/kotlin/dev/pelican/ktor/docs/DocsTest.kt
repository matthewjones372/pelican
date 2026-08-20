package dev.pelican.ktor.docs

import dev.pelican.Api
import dev.pelican.div
import dev.pelican.endpoint
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.ktor.handledNow
import dev.pelican.pathParam
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
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

private fun api() = Api(
    endpoints = listOf(getWidget handledNow { id -> Widget(id, "widget-$id") }),
    codecs = JacksonCodecs,
    title = "Widgets",
)

class DocsTest {

    @Test
    fun `the document and the page are served alongside the endpoints`() = testApplication {
        application { pelicanWithDocs(api(), Docs(docsPath = "/api-docs")) }

        val spec = client.get("/openapi.json")
        assertEquals(200, spec.status.value)
        assertEquals("application/json", spec.headers[HttpHeaders.ContentType])
        assertTrue("\"operationId\": \"getWidget\"" in spec.bodyAsText(), spec.bodyAsText().take(300))

        val page = client.get("/api-docs")
        assertEquals(200, page.status.value)
        assertTrue(page.headers[HttpHeaders.ContentType]!!.startsWith("text/html"))
        assertTrue("swagger-ui" in page.bodyAsText())

        // The endpoints are still there; the docs are an addition, not a wrapper.
        assertEquals("""{"id":3,"name":"widget-3"}""", client.get("/widgets/3").bodyAsText())
    }

    @Test
    fun `each page can be switched off on its own`() = testApplication {
        application { pelicanWithDocs(api(), Docs(docsPath = null)) }
        assertEquals(200, client.get("/openapi.json").status.value)
        assertEquals(404, client.get("/docs").status.value)
    }

    @Test
    fun `with no document route the page embeds the document instead of fetching it`() = testApplication {
        application { pelicanWithDocs(api(), Docs(openApiPath = null)) }
        assertEquals(404, client.get("/openapi.json").status.value)
        assertTrue("\"openapi\"" in client.get("/docs").bodyAsText())
    }

    @Test
    fun `with both switched off the application is the endpoints alone`() = testApplication {
        application { pelicanWithDocs(api(), Docs(openApiPath = null, docsPath = null)) }
        assertEquals(404, client.get("/docs").status.value)
        assertEquals(200, client.get("/widgets/1").status.value)
    }
}
