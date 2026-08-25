package io.github.matthewjones372.pelican.ktor.docs

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.CorsHeaders
import io.github.matthewjones372.pelican.CorsPolicy
import io.github.matthewjones372.pelican.corsPolicy
import io.github.matthewjones372.pelican.ktor.PelicanServer
import io.github.matthewjones372.pelican.ktor.pelican
import io.github.matthewjones372.pelican.ktor.start
import io.github.matthewjones372.pelican.openapi.oauth2RedirectHtml
import io.github.matthewjones372.pelican.openapi.oauth2RedirectPath
import io.github.matthewjones372.pelican.openapi.openApiJson
import io.github.matthewjones372.pelican.openapi.swaggerUiHtml
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Serving documentation is the same decision on every backend, so the setting
 * is one type: [io.github.matthewjones372.pelican.openapi.Docs], next to the page it configures. The
 * alias keeps `io.github.matthewjones372.pelican.ktor.docs.Docs` a working import.
 */
typealias Docs = io.github.matthewjones372.pelican.openapi.Docs

/**
 * The document and the page, as routes — nothing else. Add them wherever the
 * service already routes.
 *
 * This module exists for the same reason `pelican-pekko-docs` and
 * `pelican-http4k-docs` do: it is the one place that needs both halves of
 * Pelican at once — the server interpreter and the document interpreter. A
 * service that does not publish docs depends on `pelican-ktor` alone and never
 * compiles or ships the generator.
 *
 * ```
 * ordersApi().start(port = 8080)                       // endpoints only
 * ordersApi().startWithDocs(port = 8080)               // plus /openapi.json and /docs
 * ordersApi().startWithDocs(port = 8080, docs = Docs(docsPath = "/api-docs"))
 * ```
 */
fun Route.pelicanDocs(api: Api, docs: Docs = Docs()) {
    val specPath = docs.openApiPath?.takeIf { it.isNotBlank() }
    val uiPath = docs.docsPath?.takeIf { it.isNotBlank() }
    if (specPath == null && uiPath == null) return

    // Generated once and captured by the routes, rather than once per request.
    val document = api.spec().openApiJson()

    // The API's own CORS setting covers the document as well: a spec a browser
    // tool cannot fetch is the same complaint as an endpoint it cannot call.
    // Reading it is a plain GET, so there is nothing to preflight.
    val cors = api.corsPolicy()

    if (specPath != null) staticRoute(specPath, ContentType.Application.Json, document, cors)
    if (uiPath != null) {
        val redirectPath = docs.oauth?.let { oauth2RedirectPath(uiPath) }
        staticRoute(
            uiPath,
            ContentType.Text.Html,
            swaggerUiHtml(api.title, specPath.orEmpty(), document, docs.oauth, redirectPath),
            cors,
        )
        // Next to the page, so the provider has one redirect URI to register
        // and it is on the docs' own origin.
        if (redirectPath != null) {
            staticRoute(redirectPath, ContentType.Text.Html, oauth2RedirectHtml(), cors)
        }
    }
}

/**
 * The endpoints and the documentation, as one application. Docs first, so an
 * API that routes `/docs` itself does not shadow the page — though that API
 * should pass a [Docs] with different paths rather than rely on order.
 */
fun Application.pelicanWithDocs(api: Api, docs: Docs = Docs()) {
    routing {
        pelicanDocs(api, docs)
        pelican(api)
    }
}

/** [start], with the document and the Swagger UI page served alongside. */
fun Api.startWithDocs(
    port: Int = 8080,
    host: String = "0.0.0.0",
    factory: ApplicationEngineFactory<out ApplicationEngine, *> = CIO,
    docs: Docs = Docs(),
): PelicanServer = start(port, host, factory) { pelicanWithDocs(it, docs) }

/**
 * No captures, so the path is matched exactly: these are configuration, and
 * `/api-docs/oauth2-redirect.html` is as valid a setting as `/docs`.
 */
private fun Route.staticRoute(
    rawPath: String,
    contentType: ContentType,
    body: String,
    cors: CorsPolicy?,
) {
    get("/" + rawPath.trim('/')) {
        cors?.actualResponseHeaders(call.request.headers[CorsHeaders.ORIGIN])
            ?.forEach { (name, value) -> call.response.headers.append(name, value) }
        call.respondText(body, contentType)
    }
}
