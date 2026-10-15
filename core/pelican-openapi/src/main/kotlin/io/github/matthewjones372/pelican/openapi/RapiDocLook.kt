package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj

/**
 * How a RapiDoc page looks: the `<rapi-doc>` element's attributes, which are RapiDoc's whole configuration, a style
 * for the colours it keeps in CSS variables, a logo, and the stylesheets the fonts come from. See spec 0084.
 */
class RapiDocLook internal constructor(
    /** On the `<rapi-doc>` element, over Pelican's own: `render-style`, `allow-try`, `primary-color` and the rest. */
    val attributes: Map<String, String> = emptyMap(),
    /** The element's `style`: the colours RapiDoc reads from CSS variables rather than attributes. */
    val style: String? = null,
    /** In RapiDoc's `nav-logo` slot, above the navigation. */
    val logo: JsonObj? = null,
    /** Linked from the page's head, for the fonts the attributes name. */
    val stylesheets: List<String> = emptyList(),
)

/** What `rapiDocReference(path) { }` writes into. */
class RapiDocLookBuilder internal constructor() {
    private val attributes = mutableMapOf<String, String>()
    private val stylesheets = mutableListOf<String>()
    private var theme: ReferenceTheme? = null

    /** RapiDoc's own attributes, as they are, over Pelican's and the theme's. */
    fun attributes(vararg pairs: Pair<String, String>) {
        pairs.forEach { (name, value) ->
            require(name.isNotBlank()) { "A RapiDoc attribute needs a name." }
            attributes[name] = value
        }
    }

    /** A stylesheet the page links to, for the fonts the attributes name. */
    fun stylesheet(url: String) {
        require(url.isNotBlank()) { "A RapiDoc stylesheet needs a URL." }
        stylesheets += url
    }

    /** The look in slots either renderer reads; [attributes] and [stylesheet] beside it win. See spec 0082. */
    fun theme(configure: ReferenceThemeBuilder.() -> Unit) {
        val built = ReferenceThemeBuilder().apply(configure).build()
        listOf(
            "accent" to built.accent,
            "text" to built.text,
            "sidebar" to built.sidebar,
            "success" to built.success,
            "error" to built.error,
        ).forEach { (slot, colour) ->
            require(colour == null || HEX.matches(colour)) {
                "RapiDoc works out its shades from a hex colour, so a theme's $slot under rapiDocReference(...) " +
                    "is #rgb or #rrggbb; $colour is not."
            }
        }
        theme = built
    }

    internal fun build(): RapiDocLook {
        val themed = theme
        return RapiDocLook(
            attributes = themed?.rapiDocAttributes().orEmpty() + attributes,
            style = themed?.rapiDocStyle(),
            logo = themed?.logo,
            stylesheets = themed?.stylesheets.orEmpty() + stylesheets,
        )
    }
}

/** A RapiDoc reference served beside the docs page, at [path]. */
class RapiDocReference internal constructor(val path: String, val look: RapiDocLook)

private fun ReferenceTheme.rapiDocAttributes(): Map<String, String> = listOfNotNull(
    accent?.let { "primary-color" to it },
    accent?.let { "nav-accent-color" to it },
    text?.let { "text-color" to it },
    text?.let { "nav-text-color" to it },
    sidebar?.let { "nav-bg-color" to it },
    font?.let { "regular-font" to it },
    // RapiDoc fetches its own fonts from Google unless told not to; a theme that names one has its stylesheet.
    font?.let { "load-fonts" to "false" },
    codeFont?.let { "mono-font" to it },
).toMap()

/** RapiDoc keeps the status colours in CSS variables, which the element's own style overrides. */
private fun ReferenceTheme.rapiDocStyle(): String? = listOfNotNull(
    success?.let { "--green: $it;" },
    error?.let { "--red: $it;" },
).joinToString(" ").ifEmpty { null }

private val HEX = Regex("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})")
