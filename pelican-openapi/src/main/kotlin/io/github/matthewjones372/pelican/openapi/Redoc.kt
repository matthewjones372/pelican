package io.github.matthewjones372.pelican.openapi

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
fun redocHtml(title: String, specPath: String, spec: String): String {
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

    return """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>$title — API reference</title>
  <style>body { margin: 0; }</style>
</head>
<body>
  $body
  <script src="$REDOC_BUNDLE"></script>
</body>
</html>
    """.trimIndent()
}

// The major, matching the `swagger-ui-dist@5` beside it rather than disagreeing
// with it about pinning. Spec 0054 records that an exact pin is the safer one
// and that changing both is its own decision.
private const val REDOC_BUNDLE = "https://cdn.jsdelivr.net/npm/redoc@2/bundles/redoc.standalone.js"

/** An attribute value, quoted and escaped as HTML rather than as JavaScript. */
private fun attr(value: String): String =
    "\"" + value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;") + "\""

/** See `SwaggerUi.kt`: `</script>` ends a script block whatever the quotes say. */
private fun String.inlineInScript(): String = replace("</", "<\\/")
