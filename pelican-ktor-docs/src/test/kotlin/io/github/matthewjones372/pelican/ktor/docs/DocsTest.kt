package io.github.matthewjones372.pelican.ktor.docs

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ktor.handledNow
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
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
        spec.status.value shouldBe 200
        spec.headers[HttpHeaders.ContentType] shouldBe "application/json"
        withClue(spec.bodyAsText().take(300)) { spec.bodyAsText() shouldContain "\"operationId\": \"getWidget\"" }

        val page = client.get("/api-docs")
        page.status.value shouldBe 200
        page.headers[HttpHeaders.ContentType] shouldStartWith "text/html"
        page.bodyAsText() shouldContain "swagger-ui"

        // The endpoints are still there; the docs are an addition, not a wrapper.
        client.get("/widgets/3").bodyAsText() shouldBe """{"id":3,"name":"widget-3"}"""
    }

    @Test
    fun `each page can be switched off on its own`() = testApplication {
        application { pelicanWithDocs(api(), Docs(docsPath = null)) }
        client.get("/openapi.json").status.value shouldBe 200
        client.get("/docs").status.value shouldBe 404
    }

    @Test
    fun `with no document route the page embeds the document instead of fetching it`() = testApplication {
        application { pelicanWithDocs(api(), Docs(openApiPath = null)) }
        client.get("/openapi.json").status.value shouldBe 404
        client.get("/docs").bodyAsText() shouldContain "\"openapi\""
    }

    @Test
    fun `with both switched off the application is the endpoints alone`() = testApplication {
        application { pelicanWithDocs(api(), Docs(openApiPath = null, docsPath = null)) }
        client.get("/docs").status.value shouldBe 404
        client.get("/widgets/1").status.value shouldBe 200
    }
}
