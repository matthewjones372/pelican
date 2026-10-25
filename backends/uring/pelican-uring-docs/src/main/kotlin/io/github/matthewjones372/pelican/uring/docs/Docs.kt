package io.github.matthewjones372.pelican.uring.docs

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.CorsPolicy
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.corsPolicy
import io.github.matthewjones372.pelican.openapi.DocsUi
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.openapi.oauth2RedirectHtml
import io.github.matthewjones372.pelican.openapi.oauth2RedirectPath
import io.github.matthewjones372.pelican.openapi.openApiJson
import io.github.matthewjones372.pelican.openapi.redocHtml
import io.github.matthewjones372.pelican.openapi.swaggerUiHtml
import io.github.matthewjones372.pelican.spi.CorsHeaders
import io.github.matthewjones372.pelican.uring.Handlers
import io.github.matthewjones372.pelican.uring.HttpExchange
import io.github.matthewjones372.pelican.uring.PelicanServer
import io.github.matthewjones372.pelican.uring.UringHandler
import io.github.matthewjones372.pelican.uring.onlyFor
import io.github.matthewjones372.pelican.uring.start
import io.github.matthewjones372.pelican.uring.then
import io.github.matthewjones372.pelican.uring.toHandler

/** The one docs setting every backend shares, under this module's name so a move from Pekko is an import swap. */
typealias Docs = io.github.matthewjones372.pelican.openapi.Docs

/** Pekko's `TEXT_HTML_UTF8` as it renders, so a page reads the same off either server. */
private const val HTML = "text/html; charset=UTF-8"
private const val JSON = "application/json"

/** The document and the pages as a handler, and nothing else: it passes on every request but a GET of one of them. */
fun Api.docsHandler(docs: Docs = docs()): UringHandler {
    val specPath = docs.openApiPath?.takeIf { it.isNotBlank() }
    val uiPath = docs.docsPath?.takeIf { it.isNotBlank() }
    if (specPath == null && uiPath == null && docs.referencePath == null) return DocsHandler(emptyMap(), null)

    // Generated once and shared by the pages, rather than once per page.
    val document = spec().openApiJson(docs.version)

    val pages = buildMap {
        if (specPath != null) putIfAbsent(specPath.trim('/'), Page(JSON, document))
        if (uiPath != null) {
            // Null under Redoc without a second check: `docs { }` refuses that renderer an `oauth`.
            val redirectPath = docs.oauth?.let { oauth2RedirectPath(uiPath) }
            val page = when (docs.ui) {
                DocsUi.SwaggerUi -> swaggerUiHtml(title, specPath.orEmpty(), document, docs.oauth, redirectPath)
                DocsUi.Redoc -> redocHtml(title, specPath.orEmpty(), document, docs.redoc)
            }
            putIfAbsent(uiPath.trim('/'), Page(HTML, page))
            if (redirectPath != null) putIfAbsent(redirectPath.trim('/'), Page(HTML, oauth2RedirectHtml()))
        }
        docs.referencePage(title, specPath.orEmpty(), document)?.let { (path, page) ->
            putIfAbsent(path.trim('/'), Page(HTML, page))
        }
    }
    // The API's own CORS setting covers the document as well; reading it is a plain GET, so nothing to preflight.
    return DocsHandler(pages, corsPolicy()).onlyFor(Method.GET)
}

/**
 * The endpoints and the documentation as one handler, the endpoints asked first as on Pekko, so an endpoint at a
 * docs path wins. Served with `api.start(handler = { handlerWithDocs() })`.
 */
fun Api.handlerWithDocs(handlers: Handlers = Handlers.default, docs: Docs = docs()): UringHandler =
    toHandler(handlers).then(docsHandler(docs))

/** [start], with the document and the documentation page served alongside. */
fun Api.startWithDocs(
    port: Int = 8080,
    host: String = "127.0.0.1",
    docs: Docs = docs(),
    handlers: Handlers = Handlers.default,
): PelicanServer = start(port, host, handlers) { handlerWithDocs(handlers, docs) }

private class Page(val contentType: String, body: String) {
    val bytes: ByteArray = body.toByteArray()
}

/** Matched on the whole path, not by segment, because these paths are configuration and may have several. */
private class DocsHandler(private val pages: Map<String, Page>, private val cors: CorsPolicy?) : UringHandler {

    override fun handle(exchange: HttpExchange): Boolean {
        val request = exchange.request
        val page = pages[request.path.trim('/')] ?: return false
        val allowed = cors?.actualResponseHeaders(request.headers[CorsHeaders.ORIGIN]).orEmpty()
        exchange.respond(200, allowed + ("Content-Type" to page.contentType), page.bytes)
        return true
    }
}
