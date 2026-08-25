package io.github.matthewjones372.pelican.http4k.docs

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.CorsHeaders
import io.github.matthewjones372.pelican.CorsPolicy
import io.github.matthewjones372.pelican.corsPolicy
import io.github.matthewjones372.pelican.http4k.PelicanServer
import io.github.matthewjones372.pelican.http4k.StreamingSunHttp
import io.github.matthewjones372.pelican.http4k.start
import io.github.matthewjones372.pelican.http4k.toHttpHandler
import io.github.matthewjones372.pelican.openapi.oauth2RedirectHtml
import io.github.matthewjones372.pelican.openapi.oauth2RedirectPath
import io.github.matthewjones372.pelican.openapi.openApiJson
import io.github.matthewjones372.pelican.openapi.swaggerUiHtml
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.server.ServerConfig

/**
 * Serving documentation is the same decision on either backend, so the setting
 * is one type: [io.github.matthewjones372.pelican.openapi.Docs], next to the page it configures. The
 * alias keeps `io.github.matthewjones372.pelican.http4k.docs.Docs` a working import.
 */
typealias Docs = io.github.matthewjones372.pelican.openapi.Docs

/**
 * The document and the page, as routes — nothing else. Combine them yourself if
 * the service already has routes of its own.
 *
 * This module exists for the same reason `pelican-pekko-docs` does: it is the
 * one place that needs both halves of Pelican at once — the server interpreter
 * and the document interpreter. A service that does not publish docs depends on
 * `pelican-http4k` alone and never compiles or ships the generator.
 *
 * ```
 * ordersApi().start(port = 8080)                       // endpoints only
 * ordersApi().startWithDocs(port = 8080)               // plus /openapi.json and /docs
 * ordersApi().startWithDocs(port = 8080, docs = Docs(docsPath = "/api-docs"))
 * ```
 */
fun Api.docsRoutes(docs: Docs = Docs()): List<RoutingHttpHandler> {
    val specPath = docs.openApiPath?.takeIf { it.isNotBlank() }
    val uiPath = docs.docsPath?.takeIf { it.isNotBlank() }
    if (specPath == null && uiPath == null) return emptyList()

    // Generated once and shared by the routes, rather than once per route.
    val document = spec().openApiJson()

    // The API's own CORS setting covers the document as well: a spec a browser
    // tool cannot fetch is the same complaint as an endpoint it cannot call.
    // Reading it is a plain GET, so there is nothing to preflight.
    val cors = corsPolicy()

    return buildList {
        if (specPath != null) add(staticRoute(specPath, "application/json", document, cors))
        if (uiPath != null) {
            val redirectPath = docs.oauth?.let { oauth2RedirectPath(uiPath) }
            add(
                staticRoute(
                    uiPath,
                    "text/html; charset=utf-8",
                    swaggerUiHtml(title, specPath.orEmpty(), document, docs.oauth, redirectPath),
                    cors,
                ),
            )
            // Next to the page, so the provider has one redirect URI to
            // register and it is on the docs' own origin.
            if (redirectPath != null) {
                add(staticRoute(redirectPath, "text/html; charset=utf-8", oauth2RedirectHtml(), cors))
            }
        }
    }
}

/**
 * The endpoints and the documentation, as one handler.
 *
 * The docs come first so that an API which happens to route `/docs` itself does
 * not have its page shadowed — and if that is the API you are serving, pass a
 * [Docs] with different paths rather than relying on the order.
 */
fun Api.handlerWithDocs(docs: Docs = Docs()): RoutingHttpHandler =
    routes(docsRoutes(docs) + toHttpHandler())

/** [start], with the document and the Swagger UI page served alongside. */
fun Api.startWithDocs(
    port: Int = 8080,
    config: ServerConfig = StreamingSunHttp(port),
    docs: Docs = Docs(),
): PelicanServer = start(port, config) { handlerWithDocs(docs) }

/**
 * Bound as a template with no captures, so the path is matched exactly — these
 * paths are configuration, and `/api-docs/oauth2-redirect.html` is as valid a
 * setting as `/docs`.
 */
private fun staticRoute(
    rawPath: String,
    contentType: String,
    body: String,
    cors: CorsPolicy?,
): RoutingHttpHandler {
    val handler: HttpHandler = { req: Request ->
        cors?.actualResponseHeaders(req.header(CorsHeaders.ORIGIN))
            .orEmpty()
            .fold(Response(Status.OK).header("Content-Type", contentType).body(body)) { res, (name, value) ->
                res.header(name, value)
            }
    }
    return "/" + rawPath.trim('/') bind Method.GET to handler
}
