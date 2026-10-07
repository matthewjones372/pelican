package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.jsonObj

/**
 * How a Redoc page looks: Redoc's own options, passed to `Redoc.init` as they are, a logo, and the stylesheets the
 * options' fonts come from. Redoc's options are many and change between its majors, so they are its JSON rather
 * than a Pelican type that would fall behind them. See spec 0071.
 */
class RedocLook internal constructor(
    /** Passed to `Redoc.init`: `theme`, `expandResponses`, `hideDownloadButton` and the rest. */
    val options: JsonObj? = null,
    /** Redoc's `x-logo`, set on the page's copy of the document only. */
    val logo: JsonObj? = null,
    /** Linked from the page's head, for the fonts a theme names. */
    val stylesheets: List<String> = emptyList(),
) {
    internal val isPlain: Boolean get() = options == null && logo == null && stylesheets.isEmpty()

    companion object {
        /** Redoc as it comes, which is the page `redocHtml` has always served. */
        val PLAIN: RedocLook = RedocLook()
    }
}

/** What `reference(path) { }` and `redoc { }` write into. */
class RedocLookBuilder internal constructor() {
    /** Redoc's options object, passed to `Redoc.init` as it is. */
    var options: JsonObj? = null

    private var logo: JsonObj? = null
    private val stylesheets = mutableListOf<String>()

    /** Redoc's logo, above the sidebar: an image [url], which may be a `data:` URI for a small SVG. */
    fun logo(url: String, altText: String? = null, backgroundColor: String? = null) {
        require(url.isNotBlank()) { "A Redoc logo needs the URL of an image." }
        logo = jsonObj {
            "url" to url
            altText?.let { "altText" to it }
            backgroundColor?.let { "backgroundColor" to it }
        }
    }

    /** A stylesheet the page links to, for the fonts the options name. */
    fun stylesheet(url: String) {
        require(url.isNotBlank()) { "A Redoc stylesheet needs a URL." }
        stylesheets += url
    }

    internal fun build(): RedocLook = RedocLook(options, logo, stylesheets.toList())
}

/** A Redoc reference served beside the docs page, at [path]. */
class Reference internal constructor(val path: String, val look: RedocLook)
