package io.github.matthewjones372.pelican.jetty.mcp

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.jetty.toHandler
import io.github.matthewjones372.pelican.mcp.McpOptions
import io.github.matthewjones372.pelican.mcp.mcpOptions
import io.github.matthewjones372.pelican.mcp.server.MCP_PATH
import io.github.matthewjones372.pelican.mcp.server.McpHttpResponse
import io.github.matthewjones372.pelican.mcp.server.McpServer
import io.github.matthewjones372.pelican.mcp.server.mcpMethodNotAllowed
import io.github.matthewjones372.pelican.mcp.server.mcpServer
import io.github.matthewjones372.pelican.mcp.server.post
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * The tools as a handler, and nothing else: it declines every request but a POST or GET at [path].
 * The server is built here so that an endpoint MCP cannot carry is refused at startup, not on the first call.
 */
fun Api.mcpHandler(options: McpOptions = mcpOptions(), path: String = MCP_PATH): Handler =
    McpHandler(mcpServer(options), path.trim('/'), strictBodyTimeoutMillis)

/**
 * The tools and the endpoints as one handler, the tools asked first since [toHandler] answers every request.
 * Served with `api.start(handler = { handlerWithMcp() })`.
 */
fun Api.handlerWithMcp(
    handlers: Handlers = Handlers.onVirtualThreads,
    options: McpOptions = mcpOptions(),
    path: String = MCP_PATH,
): Handler = Handler.Sequence(mcpHandler(options, path), toHandler(handlers))

private class McpHandler(
    private val server: McpServer,
    private val wanted: String,
    private val timeoutMillis: Long,
) : Handler.Abstract() {

    override fun handle(request: Request, response: Response, callback: Callback): Boolean {
        if (Request.getPathInContext(request).trim('/') != wanted) return false
        when (request.method) {
            "POST" -> Content.Source.asStringAsync(request, Charsets.UTF_8)
                // The API's own wait for a strict body, as on Pekko: a JSON-RPC message is one like any other.
                .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .thenCompose(server::post)
                .whenComplete { answer, failure ->
                    if (failure != null) callback.failed(failure) else answer.writeTo(response, callback)
                }

            "GET" -> mcpMethodNotAllowed().writeTo(response, callback)

            else -> return false
        }
        return true
    }
}

private fun McpHttpResponse.writeTo(response: Response, callback: Callback) {
    response.status = status
    contentType?.let { response.headers.put(HttpHeader.CONTENT_TYPE, it) }
    response.write(true, ByteBuffer.wrap(body.toByteArray()), callback)
}
