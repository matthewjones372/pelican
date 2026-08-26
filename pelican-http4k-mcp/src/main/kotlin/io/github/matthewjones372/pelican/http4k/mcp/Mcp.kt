package io.github.matthewjones372.pelican.http4k.mcp

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.mcp.McpOptions
import io.github.matthewjones372.pelican.mcp.mcpOptions
import io.github.matthewjones372.pelican.mcp.server.MCP_PATH
import io.github.matthewjones372.pelican.mcp.server.McpHttpResponse
import io.github.matthewjones372.pelican.mcp.server.mcpMethodNotAllowed
import io.github.matthewjones372.pelican.mcp.server.mcpServer
import io.github.matthewjones372.pelican.mcp.server.post
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind

/**
 * The tools as routes — nothing else. Combine them with the endpoints yourself:
 * `routes(api.mcpRoutes() + api.toHttpHandler())`.
 *
 * The server is built once and captured by the routes: deriving the tool list
 * is where an endpoint MCP cannot carry is refused, and a refusal belongs to
 * starting the service rather than to the first call that trips over it.
 */
fun Api.mcpRoutes(options: McpOptions = mcpOptions(), path: String = MCP_PATH): List<RoutingHttpHandler> {
    val server = mcpServer(options)
    val at = "/" + path.trim('/')
    return listOf(
        // Waited on rather than composed: an http4k handler answers on the
        // calling thread, which is what the interpreter beside this does with
        // the same stage.
        at bind Method.POST to { req: Request -> response(server.post(req.bodyString()).toCompletableFuture().join()) },
        at bind Method.GET to { _: Request -> response(mcpMethodNotAllowed()) },
    )
}

private fun response(answer: McpHttpResponse): Response {
    val status = Status.fromCode(answer.status) ?: Status(answer.status, null)
    val res = Response(status).body(answer.body)
    return answer.contentType?.let { res.header("Content-Type", it) } ?: res
}
