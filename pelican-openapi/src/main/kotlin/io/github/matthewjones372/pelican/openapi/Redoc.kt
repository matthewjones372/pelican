package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.parseJson

/**
 * A Redoc page for [spec], the read-only counterpart to [swaggerUiHtml].
 *
 * With a [specPath] the page fetches the document from there, so a reader can
 * curl the same URL; without one the document is embedded, for the same reason
 * the Swagger UI page embeds it.
 *
 * Redoc has no "Try it out", so this takes no [DocsOAuth]: there is no request
 * for a token to authorize. [DocsBuilder] refuses the pair rather than letting
 * a service configure one that nothing would read.
 */
fun redocHtml(title: String, specPath: String, spec: String): String = redocHtml(title, specPath, spec, RedocLook.PLAIN)

/**
 * The same page, dressed in [look]: Redoc's own options passed to `Redoc.init`, a logo, and the stylesheets the
 * options' fonts come from. See spec 0071.
 *
 * A logo is the page's, not the document's: Redoc reads it from `info.x-logo`, so the page embeds its own copy of
 * [spec] with one set, and whatever serves [specPath] is left as it was.
 */
fun redocHtml(title: String, specPath: String, spec: String, look: RedocLook): String {
    if (look.isPlain) return plainRedoc(title, specPath, spec)

    val options = (look.options ?: JsonObj(emptyMap())).render()
    // A logo needs the document in hand, so the page embeds its copy; otherwise it fetches, as the plain page does.
    val source = when {
        look.logo != null -> withLogo(spec, look.logo).inlineInScript()
        specPath.isNotEmpty() -> JsonStr(specPath).render()
        else -> spec.inlineInScript()
    }
    val links = look.stylesheets.joinToString("\n  ") { """<link rel="stylesheet" href=${attr(it)}/>""" }
    return page(
        title,
        head = links,
        body = """<div id="ui"></div>
  <script src="$REDOC_BUNDLE"></script>
  <script>
    Redoc.init($source, ${options.inlineInScript()}, document.getElementById('ui'));
  </script>""",
    )
}

/** The document with `info.x-logo` set, for the page alone. */
private fun withLogo(spec: String, logo: JsonObj): String {
    val document = parseJson(spec) as? JsonObj ?: return spec
    val info = document["info"] as? JsonObj ?: JsonObj(emptyMap())
    return (document + jsonObj { put("info", info + jsonObj { put("x-logo", logo) }) }).render()
}

private fun plainRedoc(title: String, specPath: String, spec: String): String {
    // Redoc reads the element's attributes, so the fetching form is markup and
    // the embedded form is a call. Both were rendered before being written down.
    val body = if (specPath.isNotEmpty()) {
        """<redoc spec-url=${attr(specPath)}></redoc>"""
    } else {
        """<div id="ui"></div>
  <script>
    Redoc.init(${spec.inlineInScript()}, {}, document.getElementById('ui'));
  </script>"""
    }

    return page(title, head = "", body = "$body\n  <script src=\"$REDOC_BUNDLE\"></script>")
}

private fun page(title: String, head: String, body: String): String = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>$title — API reference</title>
  <style>
    /* Redoc draws in light only and leaves its middle panel transparent, so a reader in dark mode saw dark
       text on the browser's dark canvas. Light, on white, whatever the reader's setting. */
    :root { color-scheme: light; }
    body { margin: 0; background: #fff; }
  </style>
  $head
</head>
<body>
  $body
</body>
</html>
""".trimIndent()

// The major, matching the `swagger-ui-dist@5` beside it rather than disagreeing
// with it about pinning. Spec 0054 records that an exact pin is the safer one
// and that changing both is its own decision.
private const val REDOC_BUNDLE = "https://cdn.jsdelivr.net/npm/redoc@2/bundles/redoc.standalone.js"

/** An attribute value, quoted and escaped as HTML rather than as JavaScript. */
private fun attr(value: String): String =
    "\"" + value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;") + "\""

/** See `SwaggerUi.kt`: `</script>` ends a script block whatever the quotes say. */
private fun String.inlineInScript(): String = replace("</", "<\\/")
