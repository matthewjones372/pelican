package io.github.matthewjones372.pelican.http4k.docs

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Cors
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.cors
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.http4k.handledNow
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.http4k.core.Method
import org.http4k.core.Request
import org.junit.jupiter.api.Test

data class Widget(val id: Long, val name: String)

private val widgetId = pathParam<Long>("widgetId")

private val getWidget = endpoint(widgetId) {
    get("widgets" / widgetId)
    operationId = "getWidget"
    json<Widget>()
}

private fun api(cors: Cors? = null) = api(
    endpoints = listOf(getWidget handledNow { id -> Widget(id, "widget-$id") }),
    codecs = JacksonCodecs,
) {
    title = "Widgets"
    this.cors = cors
}

class DocsTest {

    @Test
    fun `the document and the page are served alongside the endpoints`() {
        val handler = api().handlerWithDocs(docs { docsPath = "/api-docs" })

        val spec = handler(Request(Method.GET, "/openapi.json"))
        spec.status.code shouldBe 200
        spec.header("Content-Type") shouldBe "application/json"
        withClue(spec.bodyString().take(300)) { spec.bodyString() shouldContain "\"operationId\": \"getWidget\"" }

        val page = handler(Request(Method.GET, "/api-docs"))
        page.status.code shouldBe 200
        page.header("Content-Type") shouldStartWith "text/html"
        page.bodyString() shouldContain "swagger-ui"

        // The endpoints are still there; the docs are an addition, not a wrapper.
        handler(Request(Method.GET, "/widgets/3")).bodyString() shouldBe """{"id":3,"name":"widget-3"}"""
    }

    @Test
    fun `each page can be switched off on its own`() {
        val specOnly = api().handlerWithDocs(docs { docsPath = null })
        specOnly(Request(Method.GET, "/openapi.json")).status.code shouldBe 200
        specOnly(Request(Method.GET, "/docs")).status.code shouldBe 404

        val pageOnly = api().handlerWithDocs(docs { openApiPath = null })
        pageOnly(Request(Method.GET, "/openapi.json")).status.code shouldBe 404
        // With no spec endpoint the page embeds the document rather than fetching it.
        pageOnly(Request(Method.GET, "/docs")).bodyString() shouldContain "\"openapi\""
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
        spec.header("Access-Control-Allow-Origin") shouldBe "https://tools.example.com"

        // And nothing changes for an API that never asked for CORS.
        val plain = api().handlerWithDocs()(
            Request(Method.GET, "/openapi.json").header("Origin", "https://tools.example.com"),
        )
        plain.header("Access-Control-Allow-Origin") shouldBe null
    }

    @Test
    fun `with both switched off the handler is the endpoints alone`() {
        val handler = api().handlerWithDocs(docs { openApiPath = null; docsPath = null })
        handler(Request(Method.GET, "/docs")).status.code shouldBe 404
        handler(Request(Method.GET, "/widgets/1")).status.code shouldBe 200
    }
}
