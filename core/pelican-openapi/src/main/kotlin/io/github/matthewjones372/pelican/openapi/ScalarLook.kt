package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.jsonObj

/**
 * How a Scalar page looks: Scalar's own config, passed to `createApiReference` as it is, CSS for its variables, and
 * the stylesheets the CSS's fonts come from. Scalar's config changes between minors, so it is its JSON rather than a
 * Pelican type. See spec 0080.
 */
class ScalarLook internal constructor(
    /** Passed to `Scalar.createApiReference`: `layout`, `hideDarkModeToggle`, `authentication` and the rest. */
    val options: JsonObj? = null,
    /** Scalar's `customCss`, which it applies after its own, so `--scalar-*` variables set here win. */
    val css: String? = null,
    /** Linked from the page's head, for the fonts the CSS names. */
    val stylesheets: List<String> = emptyList(),
)

/** What `scalarReference(path) { }` writes into. */
class ScalarLookBuilder internal constructor() {
    /** Scalar's config object, passed to `createApiReference` as it is, over Pelican's defaults. */
    var options: JsonObj? = null

    private var css: String? = null
    private val stylesheets = mutableListOf<String>()
    private var theme: ReferenceTheme? = null

    /** The look in slots either renderer reads; [options], [css] and [stylesheet] beside it win. See spec 0082. */
    fun theme(configure: ReferenceThemeBuilder.() -> Unit) {
        val built = ReferenceThemeBuilder().apply(configure).build()
        require(built.logo == null) {
            "Scalar has no logo, so a theme under scalarReference(...) cannot carry one. Drop the logo, or serve " +
                "Redoc with reference(...)."
        }
        theme = built
    }

    /** CSS for the page. Scalar sets its variables on `.light-mode` and `.dark-mode`, so override them there. */
    fun css(css: String) {
        require(css.isNotBlank()) { "A Scalar page's css needs some CSS." }
        this.css = css
    }

    /** A stylesheet the page links to, for the fonts the CSS names. */
    fun stylesheet(url: String) {
        require(url.isNotBlank()) { "A Scalar stylesheet needs a URL." }
        stylesheets += url
    }

    internal fun build(): ScalarLook {
        val themed = theme ?: return ScalarLook(options, css, stylesheets.toList())
        val raw = options
        return ScalarLook(
            options = if (raw == null) LIGHT else LIGHT under raw,
            css = listOfNotNull(themed.scalarCss(), css).joinToString("\n").ifEmpty { null },
            stylesheets = themed.stylesheets + stylesheets,
        )
    }
}

/** A Scalar reference served beside the docs page, at [path]. */
class ScalarReference internal constructor(val path: String, val look: ScalarLook)

/**
 * The theme as Scalar's variables, on the selectors Scalar sets its own on: a `:root` rule loses to them. Null when
 * the theme names no variable.
 */
private fun ReferenceTheme.scalarCss(): String? {
    val variables = listOfNotNull(
        accent?.let { "--scalar-color-accent: $it;" },
        text?.let { "--scalar-color-1: $it;" },
        sidebar?.let { "--scalar-sidebar-background-1: $it;" },
        success?.let { "--scalar-color-green: $it;" },
        error?.let { "--scalar-color-red: $it;" },
        font?.let { "--scalar-font: $it;" },
        codeFont?.let { "--scalar-font-code: $it;" },
    ) + derivedPalette()
    return if (variables.isEmpty()) null else ".light-mode, .dark-mode { ${variables.joinToString(" ")} }"
}

/**
 * The rest of Scalar's palette, mixed from the slots a service set: most of a page reads these, not the seven above,
 * and left alone they are Scalar's black and greys. See spec 0083.
 */
private fun ReferenceTheme.derivedPalette(): List<String> = listOfNotNull(
    accent?.let { a ->
        listOf(
            "--scalar-button-1: $a;",
            "--scalar-button-1-hover: ${mix(a, HOVER, "black")};",
            "--scalar-button-1-color: #fff;",
            "--scalar-link-color: $a;",
            "--scalar-link-color-hover: ${mix(a, HOVER, "black")};",
            "--scalar-background-accent: ${mix(a, TINT, "transparent")};",
            "--scalar-sidebar-color-active: $a;",
            "--scalar-sidebar-item-active-background: ${mix(a, ACTIVE_ITEM, "var(--scalar-sidebar-background-1)")};",
        )
    },
    text?.let { t ->
        listOf(
            "--scalar-color-2: ${mix(t, SECONDARY_TEXT, "white")};",
            "--scalar-color-3: ${mix(t, TERTIARY_TEXT, "white")};",
            "--scalar-sidebar-color-2: ${mix(t, SECONDARY_TEXT, "white")};",
        )
    },
    sidebar?.let { s ->
        listOf(
            "--scalar-background-2: $s;",
            "--scalar-background-3: ${mix(s, PANEL, "var(--scalar-color-1)")};",
            "--scalar-border-color: ${mix(s, BORDER, "var(--scalar-color-1)")};",
        )
    },
).flatten()

private fun mix(colour: String, percent: Int, with: String): String = "color-mix(in srgb, $colour $percent%, $with)"

// How much of a slot's colour each derived variable keeps, in percent.
private const val HOVER = 85
private const val TINT = 12
private const val ACTIVE_ITEM = 14
private const val SECONDARY_TEXT = 78
private const val TERTIARY_TEXT = 58
private const val PANEL = 88
private const val BORDER = 80

/** A themed page is light, as Redoc's always is: a palette picked for a light page does not survive a dark one. */
private val LIGHT: JsonObj = jsonObj {
    "forceDarkModeState" to "light"
    "hideDarkModeToggle" to true
}
