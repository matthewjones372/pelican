package example

import io.github.matthewjones372.pelican.jetty.docs.startWithDocs
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.openapi.docs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Swagger's console and a Redoc reference from one `docs { }`, over a socket: each page where it was asked for,
 * the reference in the look it was given, and the document both read left as it was. See spec 0071.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferenceBesideConsoleTest {

    private fun get(url: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `one docs block serves the console and a themed reference over the same document`() {
        val server = ordersApi().startWithDocs(
            port = 0,
            docs = docs {
                docsPath = "/api-docs"
                reference("/reference") {
                    logo("/logo.svg", altText = "Orders")
                    stylesheet("https://fonts.example/inter.css")
                    options =
                        jsonObj {
                            put(
                                "theme",
                                jsonObj {
                                    put(
                                        "colors",
                                        jsonObj {
                                            put(
                                                "primary",
                                                jsonObj {
                                                    "main" to
                                                        "#0B7A75"
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        }
                }
            },
        )
        try {
            get("${server.baseUrl}/api-docs").body() shouldContain "swagger-ui"

            val reference = get("${server.baseUrl}/reference")
            reference.statusCode() shouldBe 200
            reference.body() shouldContain "redoc.standalone.js"
            reference.body() shouldContain """"main":"#0B7A75""""
            reference.body() shouldContain """"x-logo":{"url":"/logo.svg","altText":"Orders"}"""
            reference.body() shouldContain """href="https://fonts.example/inter.css""""

            get("${server.baseUrl}/openapi.json").body() shouldNotContain "x-logo"
        } finally {
            server.stop()
        }
    }

    @Test
    fun `the example's own docs serve the console and its themed reference`() {
        val server = ordersApi().startWithDocs(port = 0, docs = ordersDocs)
        try {
            get("${server.baseUrl}/api-docs").body() shouldContain "swagger-ui"
            val reference = get("${server.baseUrl}/reference").body()
            reference shouldContain """"main":"#0B7A75""""
            reference shouldContain """"altText":"Orders""""
            reference shouldContain "family=Inter"
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a rapidoc reference beside the console reads the same document, and sends its requests to the api`() {
        val server = ordersApi().startWithDocs(
            port = 0,
            docs = docs {
                docsPath = "/api-docs"
                rapiDocReference("/reference") {
                    theme {
                        accent = "#0B7A75"
                        font("Inter, sans-serif", stylesheet = "https://fonts.example/inter.css")
                    }
                }
            },
        )
        try {
            get("${server.baseUrl}/api-docs").body() shouldContain "swagger-ui"

            val reference = get("${server.baseUrl}/reference")
            reference.statusCode() shouldBe 200
            reference.body() shouldContain "rapidoc@9.3.8/"
            reference.body() shouldContain """spec-url="/openapi.json""""
            reference.body() shouldContain """allow-try="true""""
            reference.body() shouldContain """primary-color="#0B7A75""""
            reference.body() shouldContain """href="https://fonts.example/inter.css""""
            reference.body() shouldNotContain "scalar"
        } finally {
            server.stop()
        }
    }
}
