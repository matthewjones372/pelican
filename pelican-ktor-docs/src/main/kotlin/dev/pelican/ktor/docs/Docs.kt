package dev.pelican.ktor.docs

import dev.pelican.Api
import dev.pelican.CorsHeaders
import dev.pelican.CorsPolicy
import dev.pelican.corsPolicy
import dev.pelican.ktor.PelicanServer
import dev.pelican.ktor.pelican
import dev.pelican.ktor.start
import dev.pelican.openapi.oauth2RedirectHtml
import dev.pelican.openapi.oauth2RedirectPath
import dev.pelican.openapi.openApiJson
import dev.pelican.openapi.swaggerUiHtml
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
 * is one type: [dev.pelican.openapi.Docs], next to the page it configures. The
 * alias keeps `dev.pelican.ktor.docs.Docs` a working import.
 */
typealias Docs = dev.pelican.openapi.Docs

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
        // Served next to the page, so the identity provider has one redirect
        // URI to register and it is on the same origin as the docs.
        if (redirectPath != null) {
            staticRoute(redirectPath, ContentType.Text.Html, oauth2RedirectHtml(), cors)
        }
    }
}

/**
 * The endpoints and the documentation, as one application.
 *
 * The docs are added first so that an API which happens to route `/docs` itself
 * does not have its page shadowed — and if that is the API you are serving,
 * pass a [Docs] with different paths rather than relying on the order.
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
 * Registered as a path with no captures, so it is matched exactly — these paths
 * are configuration, and `/api-docs/oauth2-redirect.html` is as valid a setting
 * as `/docs`.
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
