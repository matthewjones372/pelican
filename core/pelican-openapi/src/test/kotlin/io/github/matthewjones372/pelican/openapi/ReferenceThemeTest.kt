package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.parseJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
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

        val css = look.css.orEmpty()
        css shouldStartWith ".light-mode, .dark-mode { "
        listOf(
            "--scalar-color-accent: #C2562B;",
            "--scalar-color-1: #3B2A20;",
            "--scalar-sidebar-background-1: #FBF6EF;",
            "--scalar-color-green: #5E8C61;",
            "--scalar-color-red: #9A3A1A;",
            "--scalar-font: Nunito, sans-serif;",
            "--scalar-font-code: 'JetBrains Mono', monospace;",
        ).forEach { css shouldContain it }
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
        }.css.orEmpty().let { css ->
            css shouldStartWith ".light-mode, .dark-mode { --scalar-color-accent: #C2562B;"
            css shouldEndWith "}\n.light-mode { --scalar-color-accent: #000000; }"
        }
    }

    @Test
    fun `a logo in a Scalar theme is refused, since Scalar has none`() {
        shouldThrow<IllegalArgumentException> { scalar { theme { logo("/paw.svg") } } }.message.toString() shouldContain
            "no logo"
    }

    @Test
    fun `the rest of Scalar's palette is mixed from the accent, the text and the sidebar`() {
        val css = scalar {
            theme {
                accent = "#C2562B"
                text = "#3B2A20"
                sidebar = "#FBF6EF"
            }
        }.css.orEmpty()

        listOf(
            "--scalar-button-1: #C2562B;",
            "--scalar-button-1-hover: color-mix(in srgb, #C2562B 85%, black);",
            "--scalar-button-1-color: #fff;",
            "--scalar-link-color: #C2562B;",
            "--scalar-link-color-hover: color-mix(in srgb, #C2562B 85%, black);",
            "--scalar-background-accent: color-mix(in srgb, #C2562B 12%, transparent);",
            "--scalar-sidebar-color-active: #C2562B;",
            "--scalar-sidebar-item-active-background: " +
                "color-mix(in srgb, #C2562B 14%, var(--scalar-sidebar-background-1));",
            "--scalar-color-2: color-mix(in srgb, #3B2A20 78%, white);",
            "--scalar-color-3: color-mix(in srgb, #3B2A20 58%, white);",
            "--scalar-sidebar-color-2: color-mix(in srgb, #3B2A20 78%, white);",
            "--scalar-background-2: #FBF6EF;",
            "--scalar-background-3: color-mix(in srgb, #FBF6EF 88%, var(--scalar-color-1));",
            "--scalar-border-color: color-mix(in srgb, #FBF6EF 80%, var(--scalar-color-1));",
        ).forEach { css shouldContain it }
    }

    @Test
    fun `a slot left out derives nothing`() {
        val css = scalar { theme { text = "#3B2A20" } }.css.orEmpty()

        css shouldContain "--scalar-color-2:"
        css shouldNotContain "--scalar-button-1"
        css shouldNotContain "--scalar-background-2"
    }

    @Test
    fun `method colours, the code panel and the heading weight land where Redoc reads them`() {
        val look = redoc {
            theme {
                font("Nunito, sans-serif")
                methods {
                    get = "#5E8C61"
                    post = "#C2562B"
                    delete = "#9A3A1A"
                }
                codePanel = "#3B2A20"
                headingWeight = 800
            }
        }

        look.options shouldBe parseJson(
            """
            {"theme":{
              "colors":{"http":{"get":"#5E8C61","post":"#C2562B","delete":"#9A3A1A"}},
              "typography":{"fontFamily":"Nunito, sans-serif",
                            "headings":{"fontFamily":"Nunito, sans-serif","fontWeight":"800"}},
              "rightPanel":{"backgroundColor":"#3B2A20"}
            }}
            """,
        )
    }

    @Test
    fun `method colours, the code panel and the heading weight land where Scalar reads them`() {
        val css = scalar {
            theme {
                success = "#5E8C61"
                methods {
                    get = "#5E8C61"
                    post = "#C2562B"
                    put = "#B07D2B"
                    patch = "#8C7A3B"
                    delete = "#9A3A1A"
                }
                codePanel = "#3B2A20"
                headingWeight = 800
            }
        }.css.orEmpty()

        listOf(
            "--scalar-color-blue: #5E8C61;",
            "--scalar-color-green: #C2562B;",
            "--scalar-color-orange: #B07D2B;",
            "--scalar-color-yellow: #8C7A3B;",
            "--scalar-color-red: #9A3A1A;",
            ":is(.light-mode, .dark-mode) .examples :is(.scalar-card, .scalar-code-block) { background: #3B2A20;",
            "--scalar-color-1: color-mix(in srgb, white 92%, #3B2A20);",
            "--scalar-border-color: color-mix(in srgb, white 14%, #3B2A20);",
            ":is(.light-mode, .dark-mode) :is(h1, h2, h3) { font-weight: 800; }",
        ).forEach { css shouldContain it }
        withClue("a method shares its variable with a status under Scalar, and the method wins") {
            css.indexOf("--scalar-color-green: #C2562B;") shouldBeGreaterThan
                css.indexOf("--scalar-color-green: #5E8C61;")
        }
    }

    @Test
    fun `a blank method colour or code panel, or a weight CSS does not have, is refused where it is written`() {
        fun refusal(configure: ReferenceThemeBuilder.() -> Unit) =
            shouldThrow<IllegalArgumentException> { redoc { theme(configure) } }.message.toString()

        refusal { methods { get = "" } } shouldContain "get"
        refusal { codePanel = " " } shouldContain "codePanel"
        refusal { headingWeight = 950 } shouldContain "100 to 900"
    }

    @Test
    fun `the light page's colours stay off the dark islands Scalar draws its examples in`() {
        val css = scalar {
            theme {
                accent = "#C2562B"
                text = "#3B2A20"
                sidebar = "#FBF6EF"
                methods { get = "#5E8C61" }
            }
        }.css.orEmpty()

        val everywhere = css.substringAfter(".light-mode, .dark-mode { ").substringBefore(" }")
        val lightOnly = css.substringAfter("\n.light-mode { ").substringBefore(" }")
        listOf("--scalar-color-accent:", "--scalar-button-1:", "--scalar-link-color:", "--scalar-color-blue:")
            .forEach { everywhere shouldContain it }
        listOf("--scalar-color-1:", "--scalar-color-2:", "--scalar-background-2:", "--scalar-border-color:")
            .forEach {
                everywhere shouldNotContain it
                lightOnly shouldContain it
            }
    }
}
