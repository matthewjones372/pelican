package io.github.matthewjones372.pelican.pekko.mcp

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.mcp.McpOptions
import io.github.matthewjones372.pelican.mcp.mcpOptions
import io.github.matthewjones372.pelican.mcp.server.MCP_PATH
import io.github.matthewjones372.pelican.mcp.server.McpHttpResponse
import io.github.matthewjones372.pelican.mcp.server.McpServer
import io.github.matthewjones372.pelican.mcp.server.mcpMethodNotAllowed
import io.github.matthewjones372.pelican.mcp.server.mcpServer
import io.github.matthewjones372.pelican.mcp.server.post
import io.github.matthewjones372.pelican.pekko.toRoute
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.http.javadsl.model.ContentTypes
import org.apache.pekko.http.javadsl.model.HttpEntities
import org.apache.pekko.http.javadsl.model.HttpResponse
import org.apache.pekko.http.javadsl.server.Directives
import org.apache.pekko.http.javadsl.server.Route
import java.time.Duration

/**
 * The tools as routes — nothing else. [routeWithMcp] is these and the endpoints
 * together, which is what a service that serves both wants.
 *
 * The server is built once and captured by the routes: deriving the tool list
 * is where an endpoint MCP cannot carry is refused, and a refusal belongs to
 * starting the service rather than to the first call that trips over it.
 */
fun Api.mcpRoutes(options: McpOptions = mcpOptions(), path: String = MCP_PATH): List<Route> {
    val server = mcpServer(options)
    val wanted = path.trim('/')
    // The API's own setting, because a JSON-RPC message is a strict body like
    // any other and the service already decided how long it waits for one.
    val timeout = Duration.ofMillis(strictBodyTimeoutMillis)

    return listOf(
        Directives.post { at(wanted) { postRoute(server, timeout) } },
        Directives.get { at(wanted) { Directives.complete(response(mcpMethodNotAllowed())) } },
    )
}

/**
 * The endpoints and the tools, as one route — the sibling of `routeWithDocs`,
 * and here for the same reason: `Directives.concat` takes routes two at a time,
 * and a service should not have to know that to serve both.
 */
fun Api.routeWithMcp(
    system: ClassicActorSystemProvider,
    options: McpOptions = mcpOptions(),
    path: String = MCP_PATH,
): Route = (listOf(toRoute(system)) + mcpRoutes(options, path))
    .reduce { left, right -> Directives.concat(left, right) }

/**
 * Matched against the request path directly rather than through a path matcher,
 * as the docs routes are and for the same reason: the path is configuration,
 * and a segment matcher would only handle the one-segment case.
 */
private fun at(wanted: String, inner: () -> Route): Route = Directives.extractRequest { req ->
    if (req.uri.pathString.trim('/') != wanted) Directives.reject() else inner()
}

private fun postRoute(server: McpServer, timeout: Duration): Route =
    Directives.extractStrictEntity(timeout) { strict ->
        Directives.completeWithFuture(server.post(strict.data.utf8String()).thenApply(::response))
    }

private fun response(answer: McpHttpResponse): HttpResponse {
    val res = HttpResponse.create().withStatus(answer.status)
    // A 202 carries no entity, and Pekko's default empty one is what says so.
    return if (answer.contentType == null) res
    else res.withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, answer.body))
}
