package example

import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
 * Swagger's console and a Redoc reference from one `docs { }`, over a socket: each page where it was asked for,
 * the reference in the look it was given, and the document both read left as it was. See spec 0071.
 */
@ExtendWith(TestKitJUnit5Extension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferenceBesideConsoleTest {

    /** Found by reflection, so the field has to be a real one: `@JvmField`. */
    @JUnit5TestKit
    @JvmField
    val testKit: ActorTestKit = JUnit5TestKitBuilder().withName("reference-beside-console-test").build()

    private fun get(url: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `one docs block serves the console and a themed reference over the same document`() {
        val server = ordersApi().startWithDocs(
            testKit.system(),
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
}
