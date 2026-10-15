package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj

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

    internal fun build(): ScalarLook = ScalarLook(options, css, stylesheets.toList())
}

/** A Scalar reference served beside the docs page, at [path]. */
class ScalarReference internal constructor(val path: String, val look: ScalarLook)
