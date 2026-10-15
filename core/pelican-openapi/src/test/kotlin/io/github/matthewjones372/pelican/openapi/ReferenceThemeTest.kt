package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.parseJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** One theme, read by Redoc where Redoc reads each slot. See spec 0082. */
class ReferenceThemeTest {

    private fun redoc(configure: RedocLookBuilder.() -> Unit) = RedocLookBuilder().apply(configure).build()

    @Test
    fun `each slot lands where Redoc reads it`() {
        val look = redoc {
            theme {
                accent = "#C2562B"
                text = "#3B2A20"
                sidebar = "#FBF6EF"
                success = "#5E8C61"
                error = "#9A3A1A"
                font("Nunito, sans-serif", stylesheet = "https://fonts.example/nunito.css")
                codeFont("'JetBrains Mono', monospace")
                logo("/paw.svg", altText = "Shop")
            }
        }

        look.options shouldBe parseJson(
            """
            {"theme":{
              "colors":{"primary":{"main":"#C2562B"},"success":{"main":"#5E8C61"},"text":{"primary":"#3B2A20"},
                        "responses":{"error":{"color":"#9A3A1A"}}},
              "typography":{"fontFamily":"Nunito, sans-serif","headings":{"fontFamily":"Nunito, sans-serif"},
                            "code":{"fontFamily":"'JetBrains Mono', monospace"}},
              "sidebar":{"backgroundColor":"#FBF6EF","textColor":"#3B2A20"}
            }}
            """,
        )
        look.logo shouldBe jsonObj { "url" to "/paw.svg"; "altText" to "Shop" }
        look.stylesheets shouldBe listOf("https://fonts.example/nunito.css")
    }

    @Test
    fun `a slot left out writes nothing, and an empty theme writes no options`() {
        redoc { theme { accent = "#C2562B" } }.options shouldBe
            parseJson("""{"theme":{"colors":{"primary":{"main":"#C2562B"}}}}""")
        redoc { theme { } }.options shouldBe null
    }

    @Test
    fun `raw options win over the theme key by key, and keep what the theme does not name`() {
        val look = redoc {
            theme {
                accent = "#C2562B"
                text = "#3B2A20"
            }
            options = jsonObj {
                put("theme", jsonObj { put("colors", jsonObj { put("primary", jsonObj { "main" to "#000000" }) }) })
                "expandResponses" to "200"
            }
        }

        look.options shouldBe parseJson(
            """
            {"theme":{"colors":{"primary":{"main":"#000000"},"text":{"primary":"#3B2A20"}},
                      "sidebar":{"textColor":"#3B2A20"}},
             "expandResponses":"200"}
            """,
        )
    }

    @Test
    fun `a logo or stylesheet given beside the theme wins over the theme's`() {
        val look = redoc {
            theme {
                logo("/theme.svg")
                font("Inter", stylesheet = "https://fonts.example/inter.css")
            }
            logo("/own.svg")
            stylesheet("https://fonts.example/own.css")
        }

        look.logo shouldBe jsonObj { "url" to "/own.svg" }
        look.stylesheets shouldBe listOf("https://fonts.example/inter.css", "https://fonts.example/own.css")
    }

    @Test
    fun `a blank colour or font is refused where it is written`() {
        shouldThrow<IllegalArgumentException> { redoc { theme { accent = " " } } }.message.toString() shouldContain
            "accent"
        shouldThrow<IllegalArgumentException> { redoc { theme { font("") } } }.message.toString() shouldContain "font"
    }

    private fun scalar(configure: ScalarLookBuilder.() -> Unit) = ScalarLookBuilder().apply(configure).build()

    @Test
    fun `each slot lands on the variables Scalar reads, on the selectors it sets them on`() {
        val look = scalar {
            theme {
                accent = "#C2562B"
                text = "#3B2A20"
                sidebar = "#FBF6EF"
                success = "#5E8C61"
                error = "#9A3A1A"
                font("Nunito, sans-serif", stylesheet = "https://fonts.example/nunito.css")
                codeFont("'JetBrains Mono', monospace", stylesheet = "https://fonts.example/mono.css")
            }
        }

        look.css shouldBe ".light-mode, .dark-mode { " +
            "--scalar-color-accent: #C2562B; --scalar-color-1: #3B2A20; --scalar-sidebar-background-1: #FBF6EF; " +
            "--scalar-color-green: #5E8C61; --scalar-color-red: #9A3A1A; --scalar-font: Nunito, sans-serif; " +
            "--scalar-font-code: 'JetBrains Mono', monospace; }"
        look.stylesheets shouldBe listOf("https://fonts.example/nunito.css", "https://fonts.example/mono.css")
    }

    @Test
    fun `a themed Scalar page is light, as a themed Redoc page is, unless the options say otherwise`() {
        scalar { theme { accent = "#C2562B" } }.options shouldBe
            parseJson("""{"forceDarkModeState":"light","hideDarkModeToggle":true}""")
        scalar { theme { accent = "#C2562B" }; options = jsonObj { "forceDarkModeState" to "dark" } }.options shouldBe
            parseJson("""{"forceDarkModeState":"dark","hideDarkModeToggle":true}""")
        scalar { }.options shouldBe null
    }

    @Test
    fun `css beside the theme comes after it, so it wins`() {
        scalar {
            theme { accent = "#C2562B" }
            css(".light-mode { --scalar-color-accent: #000000; }")
        }.css shouldBe ".light-mode, .dark-mode { --scalar-color-accent: #C2562B; }\n" +
            ".light-mode { --scalar-color-accent: #000000; }"
    }

    @Test
    fun `a logo in a Scalar theme is refused, since Scalar has none`() {
        shouldThrow<IllegalArgumentException> { scalar { theme { logo("/paw.svg") } } }.message.toString() shouldContain
            "no logo"
    }
}
