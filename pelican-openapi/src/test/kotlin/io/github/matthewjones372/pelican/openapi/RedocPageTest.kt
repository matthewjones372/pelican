package io.github.matthewjones372.pelican.openapi

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/**
 * The page Redoc reads the document through. Both forms of it were rendered in
 * a browser against `example`'s document before this file was written, which is
 * what spec 0054 asked entry one to establish rather than assume.
 */
class RedocPageTest {

    private val spec = """{"openapi":"3.1.0","info":{"title":"Orders","version":"1.0.0"},"paths":{}}"""

    @Test
    fun `with a document path the page fetches it, so a reader can curl the same URL`() {
        val page = redocHtml("Orders", "/openapi.json", spec)

        page shouldContain """<redoc spec-url="/openapi.json"></redoc>"""
        page shouldNotContain "Redoc.init"
    }

    @Test
    fun `without one the document is embedded, so switching it off leaves nothing pointed at nothing`() {
        val page = redocHtml("Orders", "", spec)

        page shouldContain "Redoc.init("
        page shouldContain """"title":"Orders""""
        page shouldNotContain "spec-url"
    }

    @Test
    fun `the page names the API, as the Swagger UI page does`() {
        redocHtml("Orders", "/openapi.json", spec) shouldContain "<title>Orders — API reference</title>"
    }

    /** The same breakout `swaggerUiHtml` is held to: `</script>` ends a block whatever quotes it sits in. */
    @Test
    fun `a description carrying a closing script tag cannot break out of the embedded document`() {
        val hostile = """{"info":{"title":"x","description":"</script><script>alert(1)</script>"}}"""

        val page = redocHtml("Orders", "", hostile)

        page shouldNotContain "</script><script>alert(1)"
        page shouldContain "<\\/script>"
    }

    /** A path is an attribute here rather than a string in a script, so it escapes as HTML. */
    @Test
    fun `a document path cannot close the attribute it is written into`() {
        val page = redocHtml("Orders", """/openapi.json" onload="alert(1)""", spec)

        page shouldNotContain """onload="alert(1)""""
        page shouldContain "&quot;"
    }

    @Test
    fun `Redoc and an OAuth flow are refused together, since Redoc sends no requests`() {
        val refused = shouldThrow<IllegalArgumentException> {
            docs {
                ui = DocsUi.Redoc
                oauth = docsOAuth("docs-ui")
            }
        }

        refused.message.toString() shouldContain "DocsUi.Redoc"
        refused.message.toString() shouldContain "oauth"
    }

    @Test
    fun `either one alone is fine, and Swagger UI stays the default`() {
        docs { ui = DocsUi.Redoc }.ui shouldBe DocsUi.Redoc
        docs { oauth = docsOAuth("docs-ui") }.ui shouldBe DocsUi.SwaggerUi
        docs().ui shouldBe DocsUi.SwaggerUi
    }
}
