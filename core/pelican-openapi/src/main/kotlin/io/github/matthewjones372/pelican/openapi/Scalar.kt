package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonNull
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.parseJson

/**
 * A Scalar page for [spec]: a three-panel reference, like [redocHtml]'s, whose client sends requests to the API. See
 * spec 0080.
 *
 * With a [specPath] the page fetches the document from there; without one the document is embedded.
 */
fun scalarHtml(title: String, specPath: String, spec: String, look: ScalarLook): String {
    val source = if (specPath.isNotEmpty()) {
        jsonObj { "url" to specPath }
    } else {
        jsonObj { put("content", parseJson(spec)) }
    }
    val css = look.css?.let { jsonObj { "customCss" to it } } ?: JsonObj(emptyMap())
    val config = PELICAN_DEFAULTS + source + css + (look.options ?: JsonObj(emptyMap()))
    val links = look.stylesheets.joinToString("\n  ") { """<link rel="stylesheet" href=${attr(it)}/>""" }

    return """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>$title — API reference</title>
  <style>body { margin: 0; }</style>
  $links
</head>
<body>
  <div id="ui"></div>
  <script src="$SCALAR_BUNDLE"></script>
  <script>
    Scalar.createApiReference('#ui', ${config.render().inlineInScript()});
  </script>
</body>
</html>
    """.trimIndent()
}

/**
 * Scalar reports usage by default, and its client can route requests through Scalar's proxy. A service's requests are
 * its own, so both are off unless its options turn them back on.
 */
private val PELICAN_DEFAULTS: JsonObj = jsonObj {
    "telemetry" to false
    "proxyUrl" to JsonNull
}

// The major, as Swagger's `@5` and Redoc's `@2` are; spec 0054 owns the decision to pin exactly.
private const val SCALAR_BUNDLE = "https://cdn.jsdelivr.net/npm/@scalar/api-reference@1/dist/browser/standalone.js"

private fun attr(value: String): String =
    "\"" + value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;") + "\""

/** See `SwaggerUi.kt`: `</script>` ends a script block whatever the quotes say. */
private fun String.inlineInScript(): String = replace("</", "<\\/")
