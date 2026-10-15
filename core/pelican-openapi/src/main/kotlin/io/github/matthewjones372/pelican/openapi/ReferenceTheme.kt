package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.jsonObj

/**
 * A reference page's look in slots both renderers read, so a service can change renderer without rewriting it.
 * Anything past these slots is the renderer's own options or CSS, which win over the theme. See spec 0081.
 */
class ReferenceTheme internal constructor(
    /** Links, buttons and the selected operation. */
    val accent: String? = null,
    /** Body and sidebar text. */
    val text: String? = null,
    /** The sidebar's background. */
    val sidebar: String? = null,
    /** Success responses. */
    val success: String? = null,
    /** Error responses. */
    val error: String? = null,
    /** A CSS font-family list for text and headings. */
    val font: String? = null,
    /** A CSS font-family list for code. */
    val codeFont: String? = null,
    /** Linked from the page's head, for [font] and [codeFont]. */
    val stylesheets: List<String> = emptyList(),
    /** Redoc's `x-logo`. Scalar has no logo, so a Scalar reference refuses one. */
    val logo: JsonObj? = null,
)

/** What `theme { }` writes into. Each slot is documented on [ReferenceTheme]. */
class ReferenceThemeBuilder internal constructor() {
    var accent: String? = null
        set(value) { field = notBlank("accent", value) }
    var text: String? = null
        set(value) { field = notBlank("text", value) }
    var sidebar: String? = null
        set(value) { field = notBlank("sidebar", value) }
    var success: String? = null
        set(value) { field = notBlank("success", value) }
    var error: String? = null
        set(value) { field = notBlank("error", value) }

    private var font: String? = null
    private var fontStylesheet: String? = null
    private var codeFont: String? = null
    private var codeFontStylesheet: String? = null
    private var logo: JsonObj? = null

    /** The font for text and headings, and the [stylesheet] it is loaded from, if it is not installed. */
    fun font(family: String, stylesheet: String? = null) {
        font = notBlank("font", family)
        fontStylesheet = notBlank("font's stylesheet", stylesheet)
    }

    /** The font for code, and the [stylesheet] it is loaded from, if it is not installed. */
    fun codeFont(family: String, stylesheet: String? = null) {
        codeFont = notBlank("codeFont", family)
        codeFontStylesheet = notBlank("codeFont's stylesheet", stylesheet)
    }

    /** The logo above the sidebar: an image [url], which may be a `data:` URI for a small SVG. */
    fun logo(url: String, altText: String? = null, backgroundColor: String? = null) {
        logo = logoJson(notBlank("logo", url).orEmpty(), altText, backgroundColor)
    }

    internal fun build(): ReferenceTheme = ReferenceTheme(
        accent = accent,
        text = text,
        sidebar = sidebar,
        success = success,
        error = error,
        font = font,
        codeFont = codeFont,
        stylesheets = listOfNotNull(fontStylesheet, codeFontStylesheet),
        logo = logo,
    )

    private fun notBlank(slot: String, value: String?): String? {
        require(value == null || value.isNotBlank()) { "A theme's $slot cannot be blank; leave it out instead." }
        return value
    }
}

internal fun logoJson(url: String, altText: String?, backgroundColor: String?): JsonObj = jsonObj {
    "url" to url
    altText?.let { "altText" to it }
    backgroundColor?.let { "backgroundColor" to it }
}

/** The theme as Redoc's options; null when it names nothing Redoc reads there. */
internal fun ReferenceTheme.redocOptions(): JsonObj? {
    val colors = pruned(
        jsonObj {
            put("primary", accent?.let { jsonObj { "main" to it } })
            put("success", success?.let { jsonObj { "main" to it } })
            put("text", text?.let { jsonObj { "primary" to it } })
            put("responses", error?.let { jsonObj { put("error", jsonObj { "color" to it }) } })
        },
    )
    val typography = pruned(
        jsonObj {
            font?.let { "fontFamily" to it }
            put("headings", font?.let { jsonObj { "fontFamily" to it } })
            put("code", codeFont?.let { jsonObj { "fontFamily" to it } })
        },
    )
    val sidebarLook = pruned(
        jsonObj {
            sidebar?.let { "backgroundColor" to it }
            text?.let { "textColor" to it }
        },
    )
    val theme = pruned(
        jsonObj {
            put("colors", colors)
            put("typography", typography)
            put("sidebar", sidebarLook)
        },
    )
    return theme?.let { jsonObj { put("theme", it) } }
}

/** [over] laid on this object key by key, so a nested key [over] does not name keeps this one's value. */
internal infix fun JsonObj.under(over: JsonObj): JsonObj = JsonObj(
    (fields.keys + over.fields.keys).associateWith { key ->
        val mine = fields[key]
        val theirs = over.fields[key]
        if (mine is JsonObj && theirs is JsonObj) mine under theirs else theirs ?: checkNotNull(mine)
    },
)

private fun pruned(obj: JsonObj): JsonObj? = obj.takeUnless { it.isEmpty }
