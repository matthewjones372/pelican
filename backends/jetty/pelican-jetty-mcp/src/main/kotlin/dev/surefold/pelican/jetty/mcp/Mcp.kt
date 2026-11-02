package dev.surefold.pelican.jetty.mcp

import dev.surefold.pelican.Api
import dev.surefold.pelican.Method
import dev.surefold.pelican.jetty.Handlers
import dev.surefold.pelican.jetty.onlyFor
import dev.surefold.pelican.jetty.toHandler
import dev.surefold.pelican.jetty.withResponseHeaders
import dev.surefold.pelican.mcp.McpOptions
import dev.surefold.pelican.mcp.mcpOptions
import dev.surefold.pelican.mcp.server.MCP_PATH
import dev.surefold.pelican.mcp.server.McpHttpResponse
import dev.surefold.pelican.mcp.server.McpServer
import dev.surefold.pelican.mcp.server.mcpMethodNotAllowed
import dev.surefold.pelican.mcp.server.mcpServer
import dev.surefold.pelican.mcp.server.post
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * The tools as a handler, and nothing else: it passes on every request but a POST or GET at [path].
 * The server is built here so that an endpoint MCP cannot carry is refused at startup, not on the first call.
 */
fun Api.mcpHandler(options: McpOptions = mcpOptions(), path: String = MCP_PATH): Handler {
    val server = mcpServer(options)
    val wanted = path.trim('/')
    return Handler.Sequence(
        McpPost(server, wanted, strictBodyTimeoutMillis).onlyFor(Method.POST),
        McpGet(wanted).onlyFor(Method.GET),
    ).withResponseHeaders(this)
}

/**
 * The endpoints and the tools as one handler, the endpoints asked first as on Pekko.
 * Served with `api.start(handler = { handlerWithMcp() })`.
 */
fun Api.handlerWithMcp(
    handlers: Handlers = Handlers.default,
    options: McpOptions = mcpOptions(),
    path: String = MCP_PATH,
): Handler {
    require(admission == null) { "This interpreter does not support operation admission." }
    return Handler.Sequence(toHandler(handlers), mcpHandler(options, path))
}

private class McpPost(
    private val server: McpServer,
    private val wanted: String,
    private val timeoutMillis: Long,
) : Handler.Abstract() {

    override fun handle(request: Request, response: Response, callback: Callback): Boolean {
        if (Request.getPathInContext(request).trim('/') != wanted) return false
        Content.Source.asStringAsync(request, Charsets.UTF_8)
            // The API's own wait for a strict body, as on Pekko: a JSON-RPC message is one like any other.
            .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .thenCompose(server::post)
            .whenComplete { answer, failure ->
                if (failure != null) callback.failed(failure) else answer.writeTo(response, callback)
            }
        return true
    }
}

private class McpGet(private val wanted: String) : Handler.Abstract.NonBlocking() {

    override fun handle(request: Request, response: Response, callback: Callback): Boolean {
        if (Request.getPathInContext(request).trim('/') != wanted) return false
        mcpMethodNotAllowed().writeTo(response, callback)
        return true
    }
}

private fun McpHttpResponse.writeTo(response: Response, callback: Callback) {
    response.status = status
    contentType?.let { response.headers.put(HttpHeader.CONTENT_TYPE, it) }
    response.write(true, ByteBuffer.wrap(body.toByteArray()), callback)
}
