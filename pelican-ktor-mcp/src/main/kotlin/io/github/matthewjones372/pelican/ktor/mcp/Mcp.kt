package io.github.matthewjones372.pelican.ktor.mcp

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.mcp.McpOptions
import io.github.matthewjones372.pelican.mcp.mcpOptions
import io.github.matthewjones372.pelican.mcp.server.MCP_PATH
import io.github.matthewjones372.pelican.mcp.server.McpHttpResponse
import io.github.matthewjones372.pelican.mcp.server.mcpMethodNotAllowed
import io.github.matthewjones372.pelican.mcp.server.mcpServer
import io.github.matthewjones372.pelican.mcp.server.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.future.await

/**
 * The tools as routes — nothing else. Add them wherever the service already
 * routes, beside `pelican(api)`.
 *
 * The server is built once and captured by the routes: deriving the tool list
 * is where an endpoint MCP cannot carry is refused, and a refusal belongs to
 * starting the service rather than to the first call that trips over it.
 */
fun Route.pelicanMcp(api: Api, options: McpOptions = mcpOptions(), path: String = MCP_PATH) {
    val server = api.mcpServer(options)
    val at = "/" + path.trim('/')

    // Awaited rather than blocked on: a Ktor handler is a coroutine, and
    // parking its thread on a tool call would park one of the engine's.
    post(at) { call.answer(server.post(call.receiveText()).await()) }
    get(at) { call.answer(mcpMethodNotAllowed()) }
}

private suspend fun ApplicationCall.answer(answer: McpHttpResponse) {
    respondText(
        answer.body,
        contentType = answer.contentType?.let { ContentType.parse(it) },
        status = HttpStatusCode.fromValue(answer.status),
    )
}
