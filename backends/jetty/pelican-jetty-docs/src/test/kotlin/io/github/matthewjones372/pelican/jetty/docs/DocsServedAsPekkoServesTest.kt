package io.github.matthewjones372.pelican.jetty.docs

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.cors
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.jetty.start
import io.github.matthewjones372.pelican.openapi.DocsUi
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.openapi.docsOAuth
import io.github.matthewjones372.pelican.pathParam
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
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs as pekkoStartWithDocs
import io.github.matthewjones372.pelican.pekko.handledNow as pekkoHandledNow

data class Widget(val id: Long, val name: String)

/** One API and its docs on both servers, asked the same questions over a socket. */
class DocsServedAsPekkoServesTest {

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

        private val jettyApi = served(getWidget handledNow { id -> Widget(id, "widget-$id") })
        private val pekkoApi = served(getWidget pekkoHandledNow { id -> Widget(id, "widget-$id") })

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

        private val jetty = jettyApi.startWithDocs(port = 0, docs = console)
        private val pekko = pekkoApi.pekkoStartWithDocs(port = 0, docs = console)
        private val jettyRedoc = jettyApi.startWithDocs(port = 0, docs = redoc)
        private val pekkoRedoc = pekkoApi.pekkoStartWithDocs(port = 0, docs = redoc)
        private val off = docs {
            openApiPath = null
            docsPath = null
        }
        private val scalar = docs {
            docsPath = "/api-docs"
            scalarReference("/reference") { css(":root { --scalar-color-accent: #C2562B; }") }
        }
        private val jettyScalar = jettyApi.startWithDocs(port = 0, docs = scalar)
        private val pekkoScalar = pekkoApi.pekkoStartWithDocs(port = 0, docs = scalar)
        private val jettyOff = jettyApi.startWithDocs(port = 0, docs = off)
        private val pekkoOff = pekkoApi.pekkoStartWithDocs(port = 0, docs = off)
        private val docsAlone = jettyApi.start(port = 0) { docsHandler(console) }

        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stopAll() {
            client.close()
            listOf(jetty, jettyRedoc, jettyScalar, jettyOff, docsAlone).forEach { it.stop() }
            listOf(pekko, pekkoRedoc, pekkoScalar, pekkoOff).forEach { it.stop() }
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
    fun `swagger's console, its redirect and the reference answer as on pekko`(path: String) {
        withClue(path) { ask(jetty.baseUrl, path) shouldBe ask(pekko.baseUrl, path) }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("/spec/openapi.json", "/docs", "/openapi.json", "/docs/oauth2-redirect.html")
    fun `redoc at the docs path, under a moved document, answers as on pekko`(path: String) {
        withClue(path) { ask(jettyRedoc.baseUrl, path) shouldBe ask(pekkoRedoc.baseUrl, path) }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("/openapi.json", "/api-docs", "/reference")
    fun `a scalar reference beside the console answers as on pekko`(path: String) {
        withClue(path) { ask(jettyScalar.baseUrl, path) shouldBe ask(pekkoScalar.baseUrl, path) }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("/openapi.json", "/docs")
    fun `with every page switched off the endpoints alone answer, as on pekko`(path: String) {
        withClue(path) { ask(jettyOff.baseUrl, path) shouldBe ask(pekkoOff.baseUrl, path) }
    }

    @Test
    fun `the endpoints still answer beside the docs, as on pekko`() {
        for ((jettyUrl, pekkoUrl) in listOf(jetty.baseUrl to pekko.baseUrl, jettyOff.baseUrl to pekkoOff.baseUrl)) {
            ask(jettyUrl, "/widgets/3") shouldBe ask(pekkoUrl, "/widgets/3")
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource("PUT, /openapi.json", "POST, /api-docs", "DELETE, /reference", "PUT, /widgets/3", "OPTIONS, /api-docs")
    fun `a verb the docs do not take is refused as on pekko, naming every route's`(method: String, path: String) {
        withClue("$method $path") { ask(jetty.baseUrl, path, method) shouldBe ask(pekko.baseUrl, path, method) }
    }

    @Test
    fun `a verb nobody takes lists the api's and the docs' methods`() {
        ask(jetty.baseUrl, "/openapi.json", "PUT").allow shouldBe "GET, OPTIONS"
    }

    @Test
    fun `the pages are what was asked for, not merely the same on both`() {
        val spec = ask(jetty.baseUrl, "/openapi.json")
        spec.status shouldBe 200
        spec.allowOrigin shouldBe ORIGIN
        spec.body shouldContain "\"operationId\": \"getWidget\""
        ask(jetty.baseUrl, "/api-docs").body shouldContain "swagger-ui"
        ask(jetty.baseUrl, "/reference").body shouldContain """"x-logo":{"url":"/logo.svg","altText":"Widgets"}"""
        ask(jettyRedoc.baseUrl, "/docs").body shouldContain """href="https://fonts.example/inter.css""""
        val scalarPage = ask(jettyScalar.baseUrl, "/reference")
        scalarPage.allowOrigin shouldBe ORIGIN
        scalarPage.body shouldContain "Scalar.createApiReference"
        scalarPage.body shouldContain """"url":"/openapi.json""""
    }

    @Test
    fun `the handler alone serves the docs and declines everything else`() {
        ask(docsAlone.baseUrl, "/api-docs").status shouldBe 200
        ask(docsAlone.baseUrl, "/widgets/3").status shouldBe 404
    }
}
