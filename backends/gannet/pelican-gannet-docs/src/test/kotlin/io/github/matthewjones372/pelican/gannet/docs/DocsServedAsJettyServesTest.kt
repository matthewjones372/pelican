package io.github.matthewjones372.pelican.gannet.docs

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.cors
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.openapi.DocsUi
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.openapi.docsOAuth
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.gannet.RequiresUring
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.start
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.github.matthewjones372.pelican.jetty.docs.startWithDocs as jettyStartWithDocs
import io.github.matthewjones372.pelican.jetty.handledNow as jettyHandledNow

data class Widget(val id: Long, val name: String)

/** One API and its docs on both servers, asked the same questions over a socket. */
@RequiresUring
class DocsServedAsJettyServesTest {

    data class Seen(
        val status: Int,
        val contentType: String?,
        val allowOrigin: String?,
        val allow: String?,
        val body: String,
    )

    companion object {
        private const val ORIGIN = "https://tools.example.com"

        private val widgetId = pathParam<Long>("widgetId")

        private val getWidget = endpoint(widgetId) {
            get("widgets" / widgetId)
            operationId = "getWidget"
            json<Widget>()
        }

        private fun served(endpoint: ServerEndpoint): Api = api(listOf(endpoint), JacksonCodecs) {
            title = "Widgets"
            cors = cors(ORIGIN)
        }

        private val uringApi = served(getWidget handledNow { id -> Widget(id, "widget-$id") })
        private val jettyApi = served(getWidget jettyHandledNow { id -> Widget(id, "widget-$id") })

        private val console = docs {
            docsPath = "/api-docs"
            oauth = docsOAuth("widgets-docs") { scopes = listOf("read") }
            reference("/reference") { logo("/logo.svg", altText = "Widgets") }
        }
        private val redoc = docs {
            openApiPath = "/spec/openapi.json"
            ui = DocsUi.Redoc
            redoc { stylesheet("https://fonts.example/inter.css") }
        }

        private val uring = uringApi.startWithDocs(port = 0, docs = console)
        private val jetty = jettyApi.jettyStartWithDocs(port = 0, docs = console)
        private val uringRedoc = uringApi.startWithDocs(port = 0, docs = redoc)
        private val jettyRedoc = jettyApi.jettyStartWithDocs(port = 0, docs = redoc)
        private val off = docs {
            openApiPath = null
            docsPath = null
        }
        private val rapiDoc = docs {
            docsPath = "/api-docs"
            rapiDocReference("/reference") { theme { accent = "#C2562B" } }
        }
        private val uringRapiDoc = uringApi.startWithDocs(port = 0, docs = rapiDoc)
        private val jettyRapiDoc = jettyApi.jettyStartWithDocs(port = 0, docs = rapiDoc)
        private val uringOff = uringApi.startWithDocs(port = 0, docs = off)
        private val jettyOff = jettyApi.jettyStartWithDocs(port = 0, docs = off)
        private val docsAlone = uringApi.start(port = 0) { docsHandler(console) }

        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stopAll() {
            client.close()
            listOf(uring, uringRedoc, uringRapiDoc, uringOff, docsAlone).forEach { it.stop() }
            listOf(jetty, jettyRedoc, jettyRapiDoc, jettyOff).forEach { it.stop() }
        }
    }

    private fun ask(baseUrl: String, path: String, method: String = "GET"): Seen {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .header("Origin", ORIGIN)
            .build()
        val res = client.send(request, HttpResponse.BodyHandlers.ofString())
        val header = { name: String -> res.headers().firstValue(name).orElse(null) }
        return Seen(
            res.statusCode(),
            header("Content-Type"),
            header("Access-Control-Allow-Origin"),
            header("Allow"),
            res.body(),
        )
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        "/openapi.json",
        "/openapi.json/",
        "/api-docs",
        "/api-docs/oauth2-redirect.html",
        "/reference",
        "/docs",
        "/nobody/described/this",
    )
    fun `swagger's console, its redirect and the reference answer as on jetty`(path: String) {
        withClue(path) { ask(uring.baseUrl, path) shouldBe ask(jetty.baseUrl, path) }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("/spec/openapi.json", "/docs", "/openapi.json", "/docs/oauth2-redirect.html")
    fun `redoc at the docs path, under a moved document, answers as on jetty`(path: String) {
        withClue(path) { ask(uringRedoc.baseUrl, path) shouldBe ask(jettyRedoc.baseUrl, path) }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("/openapi.json", "/api-docs", "/reference")
    fun `a rapidoc reference beside the console answers as on jetty`(path: String) {
        withClue(path) { ask(uringRapiDoc.baseUrl, path) shouldBe ask(jettyRapiDoc.baseUrl, path) }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("/openapi.json", "/docs")
    fun `with every page switched off the endpoints alone answer, as on jetty`(path: String) {
        withClue(path) { ask(uringOff.baseUrl, path) shouldBe ask(jettyOff.baseUrl, path) }
    }

    @Test
    fun `the endpoints still answer beside the docs, as on jetty`() {
        for ((uringUrl, jettyUrl) in listOf(uring.baseUrl to jetty.baseUrl, uringOff.baseUrl to jettyOff.baseUrl)) {
            ask(uringUrl, "/widgets/3") shouldBe ask(jettyUrl, "/widgets/3")
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource("PUT, /openapi.json", "POST, /api-docs", "DELETE, /reference", "PUT, /widgets/3", "OPTIONS, /api-docs")
    fun `a verb the docs do not take is refused as on jetty, naming every route's`(method: String, path: String) {
        withClue("$method $path") { ask(uring.baseUrl, path, method) shouldBe ask(jetty.baseUrl, path, method) }
    }

    @Test
    fun `a verb nobody takes lists the api's and the docs' methods`() {
        ask(uring.baseUrl, "/openapi.json", "PUT").allow shouldBe "GET, OPTIONS"
    }

    @Test
    fun `the pages are what was asked for, not merely the same on both`() {
        val spec = ask(uring.baseUrl, "/openapi.json")
        spec.status shouldBe 200
        spec.allowOrigin shouldBe ORIGIN
        spec.body shouldContain "\"operationId\": \"getWidget\""
        ask(uring.baseUrl, "/api-docs").body shouldContain "swagger-ui"
        ask(uring.baseUrl, "/reference").body shouldContain """"x-logo":{"url":"/logo.svg","altText":"Widgets"}"""
        ask(uringRedoc.baseUrl, "/docs").body shouldContain """href="https://fonts.example/inter.css""""
        val rapiDocPage = ask(uringRapiDoc.baseUrl, "/reference")
        rapiDocPage.allowOrigin shouldBe ORIGIN
        rapiDocPage.body shouldContain "<rapi-doc "
        rapiDocPage.body shouldContain """spec-url="/openapi.json""""
        rapiDocPage.body shouldContain """allow-try="true""""
    }

    @Test
    fun `the handler alone serves the docs and declines everything else`() {
        ask(docsAlone.baseUrl, "/api-docs").status shouldBe 200
        ask(docsAlone.baseUrl, "/widgets/3").status shouldBe 404
    }
}
