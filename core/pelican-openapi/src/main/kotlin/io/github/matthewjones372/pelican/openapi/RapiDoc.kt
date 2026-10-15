package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr

/**
 * A RapiDoc page for [spec]: a reference whose console sends requests to the API, and that calls on no service of its
 * own. See spec 0084.
 *
 * With a [specPath] the page fetches the document from there; without one the page hands the document over once
 * the element is defined.
 */
fun rapiDocHtml(title: String, specPath: String, spec: String, look: RapiDocLook): String {
    val source = if (specPath.isNotEmpty()) mapOf("spec-url" to specPath) else emptyMap()
    val attributes = PELICAN_ATTRIBUTES + source + look.attributes + listOfNotNull(look.style?.let { "style" to it })
    val element = attributes.entries.joinToString(" ") { (name, value) -> "$name=${attr(value)}" }
    val logo = look.logo?.let { logoImg(it) }.orEmpty()
    val handOver = if (specPath.isNotEmpty()) {
        ""
    } else {
        """
  <script type="module">
    customElements.whenDefined('rapi-doc')
      .then(() => document.getElementById('ui').loadSpec(${spec.inlineInScript()}));
  </script>"""
    }
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
  <script type="module" src="$RAPIDOC_BUNDLE"></script>
</head>
<body>
  <rapi-doc id="ui" $element>$logo</rapi-doc>$handOver
</body>
</html>
    """.trimIndent()
}

/**
 * A console that reads this API's document and no other: the header's URL box and the file loader would let a reader
 * point the page somewhere else, and light, as a themed Redoc page is.
 */
private val PELICAN_ATTRIBUTES: Map<String, String> = mapOf(
    "allow-try" to "true",
    "render-style" to "read",
    "theme" to "light",
    "show-header" to "false",
    "allow-spec-url-load" to "false",
    "allow-spec-file-load" to "false",
)

private fun logoImg(logo: JsonObj): String {
    fun field(name: String) = (logo[name] as? JsonStr)?.value
    val url = field("url").orEmpty()
    val alt = field("altText")?.let { " alt=${attr(it)}" }.orEmpty()
    val background = field("backgroundColor")?.let { " style=${attr("background: $it;")}" }.orEmpty()
    return """<img slot="nav-logo" src=${attr(url)}$alt$background/>"""
}

// Exact, as spec 0054 recommends: 9.3.8 has been unchanged for two years, and 11 is a rewrite. See spec 0084.
private const val RAPIDOC_BUNDLE = "https://cdn.jsdelivr.net/npm/rapidoc@9.3.8/dist/rapidoc-min.js"

private fun attr(value: String): String =
    "\"" + value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;") + "\""

/** See `SwaggerUi.kt`: `</script>` ends a script block whatever the quotes say. */
private fun String.inlineInScript(): String = replace("</", "<\\/")
