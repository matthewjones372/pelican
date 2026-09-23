package example

import io.github.matthewjones372.pelican.openapi.DocsUi
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.apache.pekko.actor.testkit.typed.annotations.JUnit5TestKit
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.javadsl.JUnit5TestKitBuilder
import org.apache.pekko.actor.testkit.typed.javadsl.TestKitJUnit5Extension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The renderer is a setting, and the rest of the route is not.
 *
 * Both pages are served the same way — same path, same content type, same
 * document behind them — so what is worth asserting over a socket is that
 * choosing one gets that one and nothing of the other, and that the document
 * route is untouched by the choice.
 */
@ExtendWith(TestKitJUnit5Extension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedocPageServedTest {

    /** Found by reflection, so the field has to be a real one: `@JvmField`. */
    @JUnit5TestKit
    @JvmField
    val testKit: ActorTestKit = JUnit5TestKitBuilder().withName("redoc-page-test").build()

    private val system get() = testKit.system()

    private fun get(url: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun <T> served(
        docsPath: String,
        ui: DocsUi,
        openApiPath: String? = "/openapi.json",
        check: (String) -> T,
    ): T {
        val server = ordersApi().startWithDocs(
            system,
            port = 0,
            docs = docs {
                this.docsPath = docsPath
                this.openApiPath = openApiPath
                this.ui = ui
            },
        )
        return try {
            check(server.baseUrl)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `asking for Redoc serves Redoc, pointed at the served document`() {
        served("/api-docs", DocsUi.Redoc) { baseUrl ->
            val page = get("$baseUrl/api-docs")

            page.statusCode() shouldBe 200
            page.headers().firstValue("content-type").get() shouldStartWith "text/html"
            page.body() shouldContain """<redoc spec-url="/openapi.json"></redoc>"""
            page.body() shouldContain "redoc.standalone.js"
            page.body() shouldContain "Orders — API reference"

            // The other renderer is gone, not merely unmentioned in the markup.
            page.body() shouldNotContain "swagger-ui"
        }
    }

    @Test
    fun `the document is served exactly as it is under Swagger UI`() {
        val underRedoc = served("/api-docs", DocsUi.Redoc) { get("$it/openapi.json").body() }
        val underSwagger = served("/api-docs", DocsUi.SwaggerUi) { get("$it/openapi.json").body() }

        underRedoc shouldBe underSwagger
        underRedoc shouldContain "\"/users/{userId}\""
    }

    /** Switching the document route off embeds it, which is the form that had to be rendered to be believed. */
    @Test
    fun `with no document route the page carries the document itself`() {
        served("/api-docs", DocsUi.Redoc, openApiPath = null) { baseUrl ->
            val page = get("$baseUrl/api-docs")

            page.statusCode() shouldBe 200
            page.body() shouldContain "Redoc.init("
            page.body() shouldContain "\"/users/{userId}\""
            get("$baseUrl/openapi.json").statusCode() shouldBe 404
        }
    }

    @Test
    fun `the default is still Swagger UI, so nothing moves for a service that says nothing`() {
        served("/api-docs", DocsUi.SwaggerUi) { baseUrl ->
            get("$baseUrl/api-docs").body() shouldContain "swagger-ui"
        }
    }
}
