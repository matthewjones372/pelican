package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.parseJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** A Scalar reference, whose page sends requests, in a service's own look. See spec 0080. */
class ScalarPageTest {

    private val spec = """{"openapi":"3.1.0","info":{"title":"Shop","version":"1.0.0"},"paths":{}}"""

    private fun look(configure: ScalarLookBuilder.() -> Unit = {}) = ScalarLookBuilder().apply(configure).build()

    /** The object the page hands `Scalar.createApiReference`, read back as JSON. */
    private fun config(page: String): JsonObj {
        val call = page.substringAfter("Scalar.createApiReference('#ui', ").substringBeforeLast(");")
        return parseJson(call.replace("<\\/", "</")) as JsonObj
    }

    @Test
    fun `the document is fetched from its path, and embedded when there is none`() {
        config(scalarHtml("Shop", "/openapi.json", spec, look()))["url"] shouldBe parseJson("\"/openapi.json\"")
        config(scalarHtml("Shop", "", spec, look()))["content"] shouldBe parseJson(spec)
    }

    @Test
    fun `requests go from the browser to the API and nowhere else, unless the options say otherwise`() {
        val plain = config(scalarHtml("Shop", "/openapi.json", spec, look()))
        plain["telemetry"] shouldBe parseJson("false")
        plain["proxyUrl"] shouldBe parseJson("null")

        val opted = config(
            scalarHtml(
                "Shop",
                "/openapi.json",
                spec,
                look { options = jsonObj { "telemetry" to true; "proxyUrl" to "https://proxy.example" } },
            ),
        )
        opted["telemetry"] shouldBe parseJson("true")
        opted["proxyUrl"] shouldBe parseJson("\"https://proxy.example\"")
    }

    @Test
    fun `the options, the css and the stylesheets reach the page`() {
        val page = scalarHtml(
            "Shop",
            "/openapi.json",
            spec,
            look {
                options = jsonObj { "layout" to "modern" }
                css(":root { --scalar-color-accent: #C2562B; }")
                stylesheet("https://fonts.example/nunito.css")
            },
        )

        config(page)["layout"] shouldBe parseJson("\"modern\"")
        config(page)["customCss"] shouldBe parseJson("\":root { --scalar-color-accent: #C2562B; }\"")
        page shouldContain """<link rel="stylesheet" href="https://fonts.example/nunito.css"/>"""
        page shouldContain "@scalar/api-reference@1/"
    }

    @Test
    fun `a closing script tag in the document or the css cannot break out of the page`() {
        val hostile = """{"openapi":"3.1.0","info":{"title":"</script><script>alert(1)","version":"1"},"paths":{}}"""
        val page = scalarHtml("Shop", "", hostile, look { css("</script><script>alert(2)") })

        page shouldNotContain "</script><script>alert(1)"
        page shouldNotContain "</script><script>alert(2)"
    }

    @Test
    fun `an empty stylesheet or css is refused where it is written`() {
        shouldThrow<IllegalArgumentException> { look { stylesheet(" ") } }.message.toString() shouldContain "URL"
        shouldThrow<IllegalArgumentException> { look { css("") } }.message.toString() shouldContain "css"
    }

    @Test
    fun `a Scalar reference beside the console is a page of its own, on a path of its own`() {
        val docs = docs {
            docsPath = "/api-docs"
            scalarReference("/reference") { css(":root { --scalar-color-accent: #C2562B; }") }
        }

        docs.ui shouldBe DocsUi.SwaggerUi
        docs.scalarReference?.path shouldBe "/reference"
        docs.scalarReference?.look?.css shouldBe ":root { --scalar-color-accent: #C2562B; }"
        shouldThrow<IllegalArgumentException> { docs { scalarReference("/docs") } }.message.toString() shouldContain
            "docsPath"
        shouldThrow<IllegalArgumentException> {
            docs { scalarReference("/openapi.json") }
        }.message.toString() shouldContain "openApiPath"
        shouldThrow<IllegalArgumentException> { docs { scalarReference(" ") } }.message.toString() shouldContain
            "path"
    }

    @Test
    fun `a Scalar reference and a Redoc reference together are refused, since there is one reference page`() {
        shouldThrow<IllegalArgumentException> {
            docs {
                reference("/reference")
                scalarReference("/try")
            }
        }.message.toString() shouldContain "one reference page"
    }
}
