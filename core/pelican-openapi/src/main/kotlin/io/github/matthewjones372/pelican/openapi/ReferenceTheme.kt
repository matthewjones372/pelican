package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.jsonObj

/**
 * A reference page's look in slots both renderers read, so a service can change renderer without rewriting it.
 * Anything past these slots is the renderer's own options or CSS, which win over the theme. See spec 0082.
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
    /** Each method's badge. Under Scalar a method shares its colour with a status, and the method wins. */
    val methods: MethodColours? = null,
    /** The panel the request and response examples sit on. */
    val codePanel: String? = null,
    /** The weight of the page's headings, 100 to 900. */
    val headingWeight: Int? = null,
)

/** A colour per HTTP method, for the method badges. See spec 0083. */
class MethodColours internal constructor(
    val get: String? = null,
    val post: String? = null,
    val put: String? = null,
    val patch: String? = null,
    val delete: String? = null,
)

/** What `methods { }` writes into. */
class MethodColoursBuilder internal constructor() {
    var get: String? = null
        set(value) { field = notBlankColour("get", value) }
    var post: String? = null
        set(value) { field = notBlankColour("post", value) }
    var put: String? = null
        set(value) { field = notBlankColour("put", value) }
    var patch: String? = null
        set(value) { field = notBlankColour("patch", value) }
    var delete: String? = null
        set(value) { field = notBlankColour("delete", value) }

    internal fun build(): MethodColours = MethodColours(get, post, put, patch, delete)
}

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
    var codePanel: String? = null
        set(value) { field = notBlank("codePanel", value) }
    var headingWeight: Int? = null
        set(value) {
            require(value == null || value in LIGHTEST..HEAVIEST) {
                "A theme's headingWeight is a CSS font weight, 100 to 900; $value is not one."
            }
            field = value
        }

    private var font: String? = null
    private var fontStylesheet: String? = null
    private var codeFont: String? = null
    private var codeFontStylesheet: String? = null
    private var logo: JsonObj? = null
    private var methods: MethodColours? = null

    /** A colour for each method's badge: `methods { get = "#5E8C61"; post = "#C2562B" }`. */
    fun methods(configure: MethodColoursBuilder.() -> Unit) {
        methods = MethodColoursBuilder().apply(configure).build()
    }

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
        methods = methods,
        codePanel = codePanel,
        headingWeight = headingWeight,
    )

    private fun notBlank(slot: String, value: String?): String? = notBlankColour(slot, value)
}

private fun notBlankColour(slot: String, value: String?): String? {
    require(value == null || value.isNotBlank()) { "A theme's $slot cannot be blank; leave it out instead." }
    return value
}

private const val LIGHTEST = 100
private const val HEAVIEST = 900

internal fun logoJson(url: String, altText: String?, backgroundColor: String?): JsonObj = jsonObj {
    "url" to url
    altText?.let { "altText" to it }
    backgroundColor?.let { "backgroundColor" to it }
}

/** The theme as Redoc's options; null when it names nothing Redoc reads there. */
internal fun ReferenceTheme.redocOptions(): JsonObj? {
    val theme = pruned(
        jsonObj {
            put("colors", redocColors())
            put("typography", redocTypography())
            put("sidebar", redocSidebar())
            put("rightPanel", codePanel?.let { jsonObj { "backgroundColor" to it } })
        },
    )
    return theme?.let { jsonObj { put("theme", it) } }
}

private fun ReferenceTheme.redocColors(): JsonObj? = pruned(
    jsonObj {
        put("primary", accent?.let { jsonObj { "main" to it } })
        put("success", success?.let { jsonObj { "main" to it } })
        put("text", text?.let { jsonObj { "primary" to it } })
        put("responses", error?.let { jsonObj { put("error", jsonObj { "color" to it }) } })
        put("http", methods?.let { m -> pruned(byMethod(m)) })
    },
)

private fun ReferenceTheme.redocTypography(): JsonObj? = pruned(
    jsonObj {
        font?.let { "fontFamily" to it }
        put(
            "headings",
            pruned(
                jsonObj {
                    font?.let { "fontFamily" to it }
                    headingWeight?.let { "fontWeight" to it.toString() }
                },
            ),
        )
        put("code", codeFont?.let { jsonObj { "fontFamily" to it } })
    },
)

private fun ReferenceTheme.redocSidebar(): JsonObj? = pruned(
    jsonObj {
        sidebar?.let { "backgroundColor" to it }
        text?.let { "textColor" to it }
    },
)

/** [over] laid on this object key by key, so a nested key [over] does not name keeps this one's value. */
internal infix fun JsonObj.under(over: JsonObj): JsonObj = JsonObj(
    (fields.keys + over.fields.keys).associateWith { key ->
        val mine = fields[key]
        val theirs = over.fields[key]
        if (mine is JsonObj && theirs is JsonObj) mine under theirs else theirs ?: checkNotNull(mine)
    },
)

private fun byMethod(m: MethodColours): JsonObj = jsonObj {
    m.get?.let { "get" to it }
    m.post?.let { "post" to it }
    m.put?.let { "put" to it }
    m.patch?.let { "patch" to it }
    m.delete?.let { "delete" to it }
}

private fun pruned(obj: JsonObj): JsonObj? = obj.takeUnless { it.isEmpty }
