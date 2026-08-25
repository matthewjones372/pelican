package io.github.matthewjones372.pelican.pekko.docs

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.CorsHeaders
import io.github.matthewjones372.pelican.CorsPolicy
import io.github.matthewjones372.pelican.corsPolicy
import io.github.matthewjones372.pelican.openapi.oauth2RedirectHtml
import io.github.matthewjones372.pelican.openapi.oauth2RedirectPath
import io.github.matthewjones372.pelican.openapi.openApiJson
import io.github.matthewjones372.pelican.openapi.swaggerUiHtml
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.start
import io.github.matthewjones372.pelican.pekko.toRoute
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.javadsl.model.ContentType
import org.apache.pekko.http.javadsl.model.ContentTypes
import org.apache.pekko.http.javadsl.model.HttpEntities
import org.apache.pekko.http.javadsl.model.HttpResponse
import org.apache.pekko.http.javadsl.model.StatusCodes
import org.apache.pekko.http.javadsl.model.headers.RawHeader
import org.apache.pekko.http.javadsl.server.Directives
import org.apache.pekko.http.javadsl.server.Route

/**
 * Serving documentation is the same decision on either backend, so the setting
 * is one type: [io.github.matthewjones372.pelican.openapi.Docs], next to the page it configures.
 * The alias keeps `io.github.matthewjones372.pelican.pekko.docs.Docs` a working import.
 */
typealias Docs = io.github.matthewjones372.pelican.openapi.Docs

/**
 * The document and the page, as routes — nothing else. Combine them yourself if
 * the service already has a route of its own to concat with.
 */
fun Api.docsRoutes(docs: Docs = Docs()): List<Route> {
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
        if (specPath != null) {
            add(staticRoute(specPath, ContentTypes.APPLICATION_JSON, document, cors))
        }
        if (uiPath != null) {
            val redirectPath = docs.oauth?.let { oauth2RedirectPath(uiPath) }
            add(
                staticRoute(
                    uiPath,
                    ContentTypes.TEXT_HTML_UTF8,
                    swaggerUiHtml(title, specPath.orEmpty(), document, docs.oauth, redirectPath),
                    cors,
                ),
            )
            // Next to the page, so the provider has one redirect URI to
            // register and it is on the docs' own origin.
            if (redirectPath != null) {
                add(staticRoute(redirectPath, ContentTypes.TEXT_HTML_UTF8, oauth2RedirectHtml(), cors))
            }
        }
    }
}

/** The endpoints and the documentation, as one route. */
fun Api.routeWithDocs(system: ClassicActorSystemProvider, docs: Docs = Docs()): Route {
    val routes = listOf(toRoute(system)) + docsRoutes(docs)
    return routes.reduce { left, right -> Directives.concat(left, right) }
}

/** [start], with the document and the Swagger UI page served alongside. */
fun Api.startWithDocs(
    host: String = "127.0.0.1",
    port: Int = 8080,
    systemName: String = "pelican",
    docs: Docs = Docs(),
): PelicanServer = start(host, port, systemName) { system: ActorSystem<Void> ->
    routeWithDocs(system, docs)
}

/**
 * The same, on a system you already have. A service that brought its own
 * `ActorSystem` should not have to give up its documentation to keep it — and
 * [PelicanServer.stop] will unbind without terminating a system it borrowed.
 */
fun Api.startWithDocs(
    system: ActorSystem<Void>,
    host: String = "127.0.0.1",
    port: Int = 8080,
    docs: Docs = Docs(),
): PelicanServer = start(system, host, port) { on: ActorSystem<Void> ->
    routeWithDocs(on, docs)
}

/**
 * Matched against the request path directly rather than through a path matcher,
 * because these paths are configuration — `/api-docs/oauth2-redirect.html` is as
 * valid a setting as `/docs`, and a segment matcher would only handle the
 * one-segment case.
 */
private fun staticRoute(
    rawPath: String,
    contentType: ContentType.NonBinary,
    body: String,
    cors: CorsPolicy?,
): Route {
    val wanted = rawPath.trim('/')
    return Directives.get {
        Directives.extractRequest { req ->
            if (req.uri.pathString.trim('/') != wanted) Directives.reject()
            else {
                val origin = req.getHeader(CorsHeaders.ORIGIN).orElse(null)?.value()
                Directives.complete(
                    HttpResponse.create()
                        .withStatus(StatusCodes.OK)
                        .withEntity(HttpEntities.create(contentType, body))
                        .addHeaders(
                            cors?.actualResponseHeaders(origin)
                                .orEmpty()
                                .map { (name, value) -> RawHeader.create(name, value) },
                        ),
                )
            }
        }
    }
}
