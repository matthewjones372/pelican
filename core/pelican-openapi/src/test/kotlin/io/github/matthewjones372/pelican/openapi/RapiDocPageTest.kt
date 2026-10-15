package io.github.matthewjones372.pelican.openapi

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldContainAll
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** A RapiDoc reference, whose page sends requests and asks for nothing, in a service's own look. See spec 0084. */
class RapiDocPageTest {

    private val spec = """{"openapi":"3.1.0","info":{"title":"Shop","version":"1.0.0"},"paths":{}}"""

    private fun look(configure: RapiDocLookBuilder.() -> Unit = {}) = RapiDocLookBuilder().apply(configure).build()

    /** The `<rapi-doc>` element's attributes, read back from the page. */
    private fun attributes(page: String): Map<String, String> {
        val tag = page.substringAfter("<rapi-doc ").substringBefore(">")
        return Regex("""([a-z-]+)="([^"]*)"""").findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `the document is fetched from its path, and handed over when there is none`() {
        val fetched = rapiDocHtml("Shop", "/openapi.json", spec, look())
        attributes(fetched)["spec-url"] shouldBe "/openapi.json"
        fetched shouldNotContain "loadSpec"

        val embedded = rapiDocHtml("Shop", "", spec, look())
        attributes(embedded) shouldNotContainKey "spec-url"
        embedded shouldContain "loadSpec($spec)"
    }

    @Test
    fun `the page sends requests, reads this document and no other, and the service's attributes win`() {
        attributes(rapiDocHtml("Shop", "/openapi.json", spec, look())) shouldContainAll mapOf(
            "allow-try" to "true",
            "render-style" to "read",
            "theme" to "light",
            "show-header" to "false",
            "allow-spec-url-load" to "false",
            "allow-spec-file-load" to "false",
        )
        val focused = look { attributes("render-style" to "focused") }
        attributes(rapiDocHtml("Shop", "/openapi.json", spec, focused))["render-style"] shouldBe "focused"
    }

    @Test
    fun `each theme slot lands on the attribute RapiDoc reads it from`() {
        val page = rapiDocHtml(
            "Shop",
            "/openapi.json",
            spec,
            look {
                theme {
                    accent = "#C2562B"
                    text = "#3B2A20"
                    sidebar = "#FBF6EF"
                    success = "#5E8C61"
                    error = "#9A3A1A"
                    font("Nunito, sans-serif", stylesheet = "https://fonts.example/nunito.css")
                    codeFont("'JetBrains Mono', monospace")
                    logo("/paw.svg", altText = "Shop", backgroundColor = "#FBF6EF")
                }
            },
        )

        attributes(page) shouldContainAll mapOf(
            "primary-color" to "#C2562B",
            "nav-accent-color" to "#C2562B",
            "text-color" to "#3B2A20",
            "nav-text-color" to "#3B2A20",
            "nav-bg-color" to "#FBF6EF",
            "regular-font" to "Nunito, sans-serif",
            "mono-font" to "'JetBrains Mono', monospace",
            "load-fonts" to "false",
            "style" to "--green: #5E8C61; --red: #9A3A1A;",
        )
        page shouldContain """<img slot="nav-logo" src="/paw.svg" alt="Shop" style="background: #FBF6EF;"/>"""
        page shouldContain """<link rel="stylesheet" href="https://fonts.example/nunito.css"/>"""
    }

    @Test
    fun `a theme colour RapiDoc cannot read is refused where it is written`() {
        shouldThrow<IllegalArgumentException> { look { theme { accent = "rgb(194, 86, 43)" } } }
            .message.toString() shouldContain "#rrggbb"
        look { theme { accent = "#C52" } }
    }

    @Test
    fun `a closing script tag in the document, or a quote in an attribute, cannot break out of the page`() {
        val hostile = """{"openapi":"3.1.0","info":{"title":"</script><script>alert(1)","version":"1"},"paths":{}}"""
        rapiDocHtml("Shop", "", hostile, look()) shouldNotContain "</script><script>alert(1)"
        val quoted = look { attributes("heading-text" to "a\" onload=\"x") }
        rapiDocHtml("Shop", "/openapi.json", spec, quoted) shouldContain "heading-text=\"a&quot; onload=&quot;x\""
    }
}
