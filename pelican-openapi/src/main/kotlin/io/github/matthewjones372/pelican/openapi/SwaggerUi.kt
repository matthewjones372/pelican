// See OpenApi.kt: the `jsonObj { ... }` builders read as the JSON they emit,
// and ktlint's `wrapping` rule would break them into a staircase.
@file:Suppress("ktlint:standard:wrapping")

package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.jsonStrings

/**
 * How the docs page authenticates when a reader clicks "Authorize".
 *
 * No client secret: the page runs in a browser, so a secret shipped to it is
 * not secret. PKCE replaces it and is on by default — register the page as a
 * public client with `<docsPath>/oauth2-redirect.html` as its redirect URI.
 */
class DocsOAuth(
    val clientId: String,
    val usePkce: Boolean = true,
    /** Scopes ticked by default in the Authorize dialog. */
    val scopes: List<String> = emptyList(),
    val appName: String? = null,
    /** Extra parameters on the authorize request, e.g. `audience` for Auth0. */
    val additionalQueryStringParams: Map<String, String> = emptyMap(),
)

/**
 * A Swagger UI page for [spec]. With a [specPath] the page fetches the document
 * from there, so a reader can curl the same URL; without one the document is
 * embedded, so switching off `/openapi.json` does not leave the page pointed at
 * nothing.
 *
 * [oauthRedirectPath] becomes absolute in the page rather than here: the origin
 * the reader is on is the only one the browser will accept back, and the one
 * thing the server cannot know.
 */
fun swaggerUiHtml(
    title: String,
    specPath: String,
    spec: String,
    oauth: DocsOAuth? = null,
    oauthRedirectPath: String? = null,
): String {
    val source = if (specPath.isNotEmpty()) "url: '$specPath'" else "spec: ${spec.inlineInScript()}"
    val redirect = if (oauth != null && oauthRedirectPath != null) {
        ", oauth2RedirectUrl: window.location.origin + ${js(oauthRedirectPath)}"
    } else ""
    val initOAuth = if (oauth == null) "" else "\n    window.ui.initOAuth(${oauthConfig(oauth)});"

    return """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>$title — API reference</title>
  <link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@5/swagger-ui.css"/>
  <style>body { margin: 0; }</style>
</head>
<body>
  <div id="ui"></div>
  <script src="https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
  <script>
    window.ui = SwaggerUIBundle({ $source, dom_id: '#ui', deepLinking: true$redirect });$initOAuth
  </script>
</body>
</html>
    """.trimIndent()
}

/**
 * Where the identity provider sends the reader back to. Runs in the pop-up
 * Swagger UI opened, hands the code to the opener and closes; nothing is
 * stored, and the token exchange happens in the opener.
 *
 * `state` is checked rather than accepted, because a response the page did not
 * ask for is what a CSRF against the flow looks like.
 */
fun oauth2RedirectHtml(): String = """
<!doctype html>
<html lang="en">
<head><meta charset="utf-8"/><title>Authorizing…</title></head>
<body>
<script>
  (function () {
    var opener = window.opener && window.opener.swaggerUIRedirectOauth2;
    if (!opener) { document.body.innerText = 'Nothing opened this page.'; return; }

    // The code flow answers in the query string; the implicit flow in the
    // fragment, which never leaves the browser.
    var raw = window.location.hash
      ? window.location.hash.substring(1)
      : window.location.search.substring(1);

    var answer = {};
    raw.split('&').forEach(function (pair) {
      if (!pair) return;
      var i = pair.indexOf('=');
      var k = i < 0 ? pair : pair.substring(0, i);
      var v = i < 0 ? '' : pair.substring(i + 1);
      answer[decodeURIComponent(k)] = decodeURIComponent(v.replace(/\+/g, ' '));
    });

    var stateMatches = answer.state === opener.state;
    var flow = opener.auth && opener.auth.schema && opener.auth.schema.get('flow');
    var isCodeFlow = flow === 'authorizationCode' || flow === 'authorization_code' || flow === 'accessCode';

    if (answer.error) {
      opener.errCb({
        authId: opener.auth.name,
        source: 'auth',
        level: 'error',
        message: answer.error_description || answer.error
      });
    } else if (!stateMatches) {
      opener.errCb({
        authId: opener.auth.name,
        source: 'auth',
        level: 'warning',
        message: 'Authorization response has the wrong state and was rejected.'
      });
    } else if (isCodeFlow && !opener.auth.code) {
      if (answer.code) {
        opener.auth.code = answer.code;
        opener.callback({ auth: opener.auth, redirectUrl: opener.redirectUrl });
      } else {
        opener.errCb({
          authId: opener.auth.name,
          source: 'auth',
          level: 'error',
          message: 'Authorization succeeded but returned no code.'
        });
      }
    } else {
      opener.callback({ auth: opener.auth, token: answer, isValid: stateMatches, redirectUrl: opener.redirectUrl });
    }
    window.close();
  })();
</script>
</body>
</html>
""".trimIndent()

/** The redirect page's path, given where the docs page itself is served. */
fun oauth2RedirectPath(docsPath: String): String =
    "/" + docsPath.trim('/') + "/oauth2-redirect.html"

private fun oauthConfig(o: DocsOAuth): String = jsonObj {
    "clientId" to o.clientId
    "usePkceWithAuthorizationCodeGrant" to o.usePkce
    if (o.scopes.isNotEmpty()) put("scopes", jsonStrings(o.scopes))
    putIfNotNull("appName", o.appName)
    if (o.additionalQueryStringParams.isNotEmpty()) {
        put("additionalQueryStringParams", jsonObj {
            o.additionalQueryStringParams.forEach { (k, v) -> put(k, JsonStr(v)) }
        })
    }
}.render().inlineInScript()

private fun js(value: String): String = JsonStr(value).render()

/**
 * `</script>` ends a script block whatever the surrounding quotes say, so a
 * description containing one would break out of the page. The escape is
 * invisible to a JSON parser.
 */
private fun String.inlineInScript(): String = replace("</", "<\\/")
