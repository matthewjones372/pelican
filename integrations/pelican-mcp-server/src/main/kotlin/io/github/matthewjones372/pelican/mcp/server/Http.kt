package io.github.matthewjones372.pelican.mcp.server

import io.github.matthewjones372.pelican.JsonNull
import java.util.concurrent.CompletionStage

/**
 * One answer to one HTTP request carrying JSON-RPC. A backend module turns this
 * into that backend's response and does nothing else, which is what keeps the
 * protocol in one place and each mounting a dozen lines.
 */
class McpHttpResponse internal constructor(
    val status: Int,
    /** Null where there is no body: a notification is accepted, and there is nothing to say back. */
    val contentType: String?,
    val body: String,
)

/**
 * MCP's Streamable HTTP transport, the request/response half: one POST carrying
 * one JSON-RPC message, answered with one JSON-RPC message.
 *
 * The server-initiated half is deliberately not here. A server may answer a
 * POST with an event stream and may offer a GET that opens one, and both mean
 * keeping a session id, numbering events and replaying them from
 * `Last-Event-ID` — state this endpoint does not have. A tools-only server has
 * nothing to push either: a tool call has one result, which is the same reason
 * a streamed endpoint is not a tool. [mcpMethodNotAllowed] is what a GET is
 * answered with, which is what the specification names for a server with no
 * stream to open.
 */
fun McpServer.post(message: String): CompletionStage<McpHttpResponse> =
    handle(message).thenApply { answer ->
        if (answer == null) ACCEPTED else McpHttpResponse(OK, JSON, answer)
    }

/**
 * What anything but a POST is answered with: a 405, and a sentence saying what
 * this endpoint does answer. A 404 would say the tools are not here, which is
 * the one thing that is not true.
 */
fun mcpMethodNotAllowed(): McpHttpResponse = McpHttpResponse(
    METHOD_NOT_ALLOWED,
    JSON,
    failure(
        JsonNull,
        INVALID_REQUEST,
        "This endpoint answers a POST carrying one JSON-RPC message. There is no event stream to open " +
            "with a GET and no session to end with a DELETE.",
    ),
)

/**
 * Where a client expects to find the endpoint unless it was told otherwise. One
 * path for the three backends to default to, so a service that moves between
 * them keeps the URL a client was configured with.
 */
const val MCP_PATH: String = "/mcp"

/** A notification is accepted and answered with nothing, which over HTTP is a 202 and no body. */
private val ACCEPTED = McpHttpResponse(202, null, "")

private const val OK = 200
private const val METHOD_NOT_ALLOWED = 405
private const val JSON = "application/json"
