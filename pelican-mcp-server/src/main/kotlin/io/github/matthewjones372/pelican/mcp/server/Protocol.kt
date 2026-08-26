package io.github.matthewjones372.pelican.mcp.server

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.JsonNull
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.emptyJsonObj
import io.github.matthewjones372.pelican.jsonArr
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.mcp.McpDispatch
import io.github.matthewjones372.pelican.mcp.McpOptions
import io.github.matthewjones372.pelican.mcp.ToolResult
import io.github.matthewjones372.pelican.mcp.mcpDispatch
import io.github.matthewjones372.pelican.mcp.mcpOptions
import io.github.matthewjones372.pelican.mcp.toJson
import io.github.matthewjones372.pelican.operationName
import io.github.matthewjones372.pelican.parseJson
import io.github.matthewjones372.pelican.spi.classifyError
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * The revision of the Model Context Protocol this speaks, and the one
 * `initialize` answers with unless the client asked for another it understands.
 *
 * `2025-11-25` is `LATEST_PROTOCOL_VERSION` in the official Kotlin SDK
 * (`io.modelcontextprotocol:kotlin-sdk:0.15.0`), which is what pins it: a
 * revision no client library on the other end supports is a number in a
 * handshake and nothing more.
 */
const val MCP_PROTOCOL_VERSION: String = "2025-11-25"

/**
 * The revisions `initialize` will agree to rather than answer past. `tools`,
 * `tools/list` and `tools/call` say the same thing in both, which is all this
 * serves; a client asking for anything else is told which revision this speaks
 * and decides for itself whether to go on.
 */
private val SUPPORTED_REVISIONS = setOf(MCP_PROTOCOL_VERSION, "2025-06-18")

/**
 * A service as an MCP server: one JSON-RPC message in, the one to send back.
 *
 * Transport-free on purpose — [mcpServe] drives it over stdio and [post] over
 * HTTP, and both are a handful of lines because everything that decides what a
 * tool call *does* already lives in [McpDispatch].
 */
class McpServer internal constructor(private val api: Api, private val dispatch: McpDispatch) {

    /**
     * The answer to one message, or null where the message was a notification —
     * which JSON-RPC answers with silence.
     */
    fun handle(message: String): CompletionStage<String?> {
        val parsed = try {
            parseJson(message)
        } catch (e: IllegalArgumentException) {
            return completed(failure(JsonNull, PARSE_ERROR, "The message is not JSON: ${e.message}"))
        } catch (e: IllegalStateException) {
            return completed(failure(JsonNull, PARSE_ERROR, "The message is not JSON: ${e.message}"))
        }
        return answer(parsed)
    }

    private fun answer(parsed: JsonValue): CompletionStage<String?> {
        val request = parsed as? JsonObj ?: return completed(
            failure(
                JsonNull,
                INVALID_REQUEST,
                "A JSON-RPC message is one object. A batch is several, and this answers one message with one.",
            ),
        )
        // A notification carries no id and takes no answer. The ones a
        // tools-only server is sent — `initialized`, `cancelled` — need nothing
        // done, and answering one is a message the client will not match up.
        val id = request["id"] ?: return completed(null)
        val method = (request["method"] as? JsonStr)?.value
            ?: return completed(failure(id, INVALID_REQUEST, "A JSON-RPC request names a `method`."))
        val params = request["params"] as? JsonObj ?: emptyJsonObj

        return when (method) {
            "initialize" -> completed(reply(id, initialize(params)))

            "tools/list" -> completed(reply(id, jsonObj { put("tools", jsonArr(dispatch.tools.map { it.toJson() })) }))

            "tools/call" -> call(id, params)

            // Cheap, and the one way a client can tell a live session from one
            // whose process is still running with nothing behind it.
            "ping" -> completed(reply(id, emptyJsonObj))

            else -> completed(
                failure(
                    id,
                    METHOD_NOT_FOUND,
                    "'$method' is not something this serves. It answers initialize, tools/list, " +
                        "tools/call and ping: an endpoint description says what a tool is, and " +
                        "resources, prompts and sampling are not that.",
                ),
            )
        }
    }

    /**
     * `capabilities` publishes tools and nothing else, `listChanged` false: the
     * tools come from descriptions fixed when the service was built, so a
     * client that polls for changes would poll forever.
     */
    private fun initialize(params: JsonObj): JsonObj {
        val asked = (params["protocolVersion"] as? JsonStr)?.value
        return jsonObj {
            "protocolVersion" to (asked?.takeIf { it in SUPPORTED_REVISIONS } ?: MCP_PROTOCOL_VERSION)
            put("capabilities", jsonObj { put("tools", jsonObj { "listChanged" to false }) })
            put(
                "serverInfo",
                jsonObj {
                    "name" to api.title
                    "version" to api.version
                },
            )
        }
    }

    private fun call(id: JsonValue, params: JsonObj): CompletionStage<String?> {
        val name = (params["name"] as? JsonStr)?.value ?: return completed(
            failure(id, INVALID_PARAMS, "tools/call takes the tool's name as `params.name`."),
        )
        val arguments = params["arguments"] as? JsonObj ?: emptyJsonObj
        return invoke(name, arguments).handle { result, thrown ->
            if (thrown == null) reply(id, result.toJson()) else threw(id, name, thrown)
        }
    }

    /**
     * A handler that throws before returning its stage throws through
     * [McpDispatch.call] itself; composing off a completed stage puts that on
     * the same path an asynchronous failure takes, so the `handle` above
     * answers both.
     */
    private fun invoke(name: String, arguments: JsonObj): CompletionStage<ToolResult> =
        CompletableFuture.completedStage(Unit).thenCompose { dispatch.call(name, arguments) }

    /**
     * What a throwable becomes, decided by the same table the three backends
     * answer requests from.
     *
     * A described failure — an `ApiException` from a filter, a decode that a
     * refinement refused — is a result carrying `isError`: a model can read the
     * status and the sentence and try again. What nobody described is a
     * protocol error instead, because a bug in a handler is not an answer to
     * give a model, and it carries the reference [Api.onServerError] was handed
     * so that one log line covers both halves of the service.
     *
     * The classification is taken and nothing beside it, which is why this is
     * [classifyError] rather than the render the three backends do. A tool call
     * is answered in JSON-RPC, whose envelope MCP fixes and a service does not
     * choose: `refusals(...)` reaches the HTTP wire and stops there, and so does
     * `onRefusal(...)`. A refused tool call went out as a 200 carrying a result,
     * so counting it as a refused *request* would put a status on a meter that
     * no caller was ever sent.
     */
    private fun threw(id: JsonValue, tool: String, thrown: Throwable): String {
        val rendered = classifyError(thrown, api)
        val unexpected = rendered.unexpected
            ?: return reply(id, ToolResult(described(rendered.error), isError = true).toJson())

        val reference = checkNotNull(rendered.reference) { "an unexpected failure with no reference" }
        // Core has no logger to fall back on, so where no hook is set the
        // reference in the answer is the only record — as it is in-memory.
        api.onServerError?.invoke(reference, endpointNamed(tool), unexpected)
        return failure(id, INTERNAL_ERROR, "${rendered.error.error}. ${rendered.error.detail}")
    }

    private fun endpointNamed(tool: String): Endpoint<*, *>? =
        api.endpoints.firstOrNull { it.endpoint.operationName == tool }?.endpoint
}

/**
 * The service as a server a model's client can talk to. Descriptions, dispatch
 * and the protocol all come from the one [Api], so a tool this lists is a tool
 * it can run.
 */
fun Api.mcpServer(options: McpOptions = mcpOptions()): McpServer = McpServer(this, mcpDispatch(options))

/**
 * One tool result as `tools/call` publishes it: the text every client reads,
 * and the same answer as data where the tool published a schema binding it.
 */
fun ToolResult.toJson(): JsonObj = jsonObj {
    put(
        "content",
        jsonArr(
            listOf(
                jsonObj {
                    "type" to "text"
                    "text" to text
                },
            ),
        ),
    )
    put("structuredContent", structuredContent)
    "isError" to isError
}

/** The status and the sentence, as [McpDispatch] writes a declared failure. */
private fun described(error: ApiError): String =
    "${error.status} ${error.error}" + error.detail?.let { ": $it" }.orEmpty()

private fun reply(id: JsonValue, result: JsonObj): String = jsonObj {
    "jsonrpc" to JSONRPC_VERSION
    put("id", id)
    put("result", result)
}.render()

/** A JSON-RPC error message. Internal because the HTTP half answers a 405 with one. */
internal fun failure(id: JsonValue, code: Int, message: String): String = jsonObj {
    "jsonrpc" to JSONRPC_VERSION
    put("id", id)
    put(
        "error",
        jsonObj {
            "code" to code
            "message" to message
        },
    )
}.render()

private fun completed(message: String?): CompletionStage<String?> = CompletableFuture.completedStage(message)

private const val JSONRPC_VERSION = "2.0"

// The codes JSON-RPC 2.0 reserves, which are the ones a client's own error
// handling is written against.
internal const val PARSE_ERROR = -32700
internal const val INVALID_REQUEST = -32600
internal const val METHOD_NOT_FOUND = -32601
internal const val INVALID_PARAMS = -32602
internal const val INTERNAL_ERROR = -32603
