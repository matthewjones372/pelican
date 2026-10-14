package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.jsonObj
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** A Redoc page in a service's own look, and a reference served beside the console. See spec 0071. */
class RedocLookTest {

    private val spec = """{"openapi":"3.1.0","info":{"title":"Shop","version":"1.0.0"},"paths":{}}"""

    private fun look(configure: RedocLookBuilder.() -> Unit) = RedocLookBuilder().apply(configure).build()

    private val primary = jsonObj {
        put("theme", jsonObj { put("colors", jsonObj { put("primary", jsonObj { "main" to "#D9822B" }) }) })
    }

    @Test
    fun `the options reach Redoc as they were given, and the document is fetched from its path`() {
        val page = redocHtml(
            "Shop",
            "/openapi.json",
            spec,
            look { options = primary },
        )

        page shouldContain "Redoc.init(\"/openapi.json\", " + primary.render()
        page shouldNotContain "<redoc spec-url"
    }

    @Test
    fun `a logo is set on the page's own copy of the document`() {
        val page = redocHtml("Shop", "/openapi.json", spec, look { logo("data:image/svg;utf8,paw", altText = "Shop") })

        page shouldContain """"x-logo":{"url":"data:image/svg;utf8,paw","altText":"Shop"}"""
        page shouldContain """"title":"Shop""""
    }

    @Test
    fun `a stylesheet is linked from the head, for the fonts a theme names`() {
        val page = redocHtml("Shop", "", spec, look { stylesheet("https://fonts.example/nunito.css") })

        page shouldContain """<link rel="stylesheet" href="https://fonts.example/nunito.css"/>"""
    }

    @Test
    fun `with no look the page is the one redocHtml always served`() {
        redocHtml("Shop", "/openapi.json", spec, RedocLook.PLAIN) shouldBe redocHtml("Shop", "/openapi.json", spec)
    }

    @Test
    fun `options carrying a closing script tag cannot break out of the page`() {
        val hostile = jsonObj { "hideHostname" to "</script><script>alert(1)" }
        val page = redocHtml("Shop", "", spec, look { options = hostile })

        page shouldNotContain "</script><script>alert(1)"
    }

    @Test
    fun `a reference beside the console is a page of its own, on a path of its own`() {
        val docs = docs {
            docsPath = "/api-docs"
            reference("/reference") { logo("/paw.svg") }
        }

        docs.ui shouldBe DocsUi.SwaggerUi
        docs.reference?.path shouldBe "/reference"
        shouldThrow<IllegalArgumentException> { docs { reference("/docs") } }.message.toString() shouldContain
            "docsPath"
        shouldThrow<IllegalArgumentException> { docs { reference("/openapi.json") } }.message.toString() shouldContain
            "openApiPath"
    }

    @Test
    fun `a look for the docs page is refused when that page is not Redoc's`() {
        shouldThrow<IllegalArgumentException> { docs { redoc { logo("/paw.svg") } } }.message.toString() shouldContain
            "ui = DocsUi.Redoc"
        docs {
            ui = DocsUi.Redoc
            redoc { logo("/paw.svg") }
        }.redoc.logo shouldBe jsonObj { "url" to "/paw.svg" }
    }
}
