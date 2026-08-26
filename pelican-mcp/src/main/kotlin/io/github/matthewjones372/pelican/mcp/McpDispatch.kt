package io.github.matthewjones372.pelican.mcp

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.BodyDecodeFailure
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.DeclaredResponses
import io.github.matthewjones372.pelican.DecodeFailure
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.ErrorOutput
import io.github.matthewjones372.pelican.JsonBool
import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonOutput
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.Output
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.QueryParam
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.emptyJsonObj
import io.github.matthewjones372.pelican.operationName
import io.github.matthewjones372.pelican.parseJson
import io.github.matthewjones372.pelican.payloadType
import io.github.matthewjones372.pelican.schema.StandaloneSchemas
import io.github.matthewjones372.pelican.spi.decodeList
import io.github.matthewjones372.pelican.spi.failureNamedBy
import io.github.matthewjones372.pelican.spi.handlerFor
import io.github.matthewjones372.pelican.spi.successNamedBy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * What one tool call produced.
 *
 * This module's own value rather than the SDK's `CallToolResult`, for the
 * reason [McpTool] is: mapping one onto the other is a few lines wherever
 * these are served, and core-only code that depends on an SDK type is not
 * possible at all.
 */
class ToolResult(
    /** The answer as text, which is the one thing every client reads. */
    val text: String,
    /** The same answer as data, where the tool published an `outputSchema` it validates against. */
    val structuredContent: JsonObj? = null,
    /** A failure the model is meant to read and try again against, not a transport error. */
    val isError: Boolean = false,
)

/**
 * The bound endpoints as callable tools: arguments decoded exactly as a request
 * would decode them, then the handler the route already has, filters and all.
 */
class McpDispatch internal constructor(private val bound: Map<String, BoundTool>) {

    /** What to publish. Derived from the same endpoints [call] dispatches to. */
    val tools: List<McpTool> get() = bound.values.map { it.tool }

    /**
     * Runs one tool call.
     *
     * Everything a model could get wrong — an unknown name, a missing
     * argument, a value a refinement rejects, a declared failure — comes back
     * as a [ToolResult] carrying `isError`, because a model can read one and
     * try again. What nobody declared still throws: a bug in a handler is not
     * something to answer a model with.
     */
    fun call(name: String, arguments: JsonObj = emptyJsonObj): CompletionStage<ToolResult> {
        val tool = bound[name] ?: return completed(unknownTool(name))
        val params = try {
            tool.paramsFrom(arguments)
        } catch (e: DecodeFailure) {
            return completed(refused(e.message))
        } catch (e: BodyDecodeFailure) {
            return completed(refused(e.message))
        } catch (e: ApiException) {
            return completed(refused(e.message))
        }
        return tool.invoke(params).thenApply { tool.resultOf(it) }
    }

    private fun unknownTool(name: String) = refused(
        "There is no tool called '$name'. This service publishes ${bound.keys.joinToString()}.",
    )
}

/** Descriptions and dispatch come from one place, so a tool list cannot advertise a call this refuses. */
fun Api.mcpDispatch(options: McpOptions = mcpOptions()): McpDispatch {
    val included = endpoints.filter { options.include(it.endpoint) }
    val schemas = StandaloneSchemas(codecs)
    val bound = included.associate { se ->
        se.endpoint.operationName to BoundTool(se, options, codecs, schemas, handlerFor(se))
    }
    require(bound.size == included.size) {
        val clashes = included.groupBy { it.endpoint.operationName }.filterValues { it.size > 1 }.keys
        "Two endpoints are called ${clashes.joinToString()}, and a tool name is one name: the second " +
            "would replace the first. Give them operationIds of their own."
    }
    return McpDispatch(bound)
}

/** One endpoint, its description, and the handler the route runs. */
internal class BoundTool(
    private val server: ServerEndpoint,
    private val options: McpOptions,
    private val codecs: Codecs,
    schemas: StandaloneSchemas,
    private val handler: (Params) -> CompletionStage<Any?>,
) {
    private val endpoint: Endpoint<*, *> get() = server.endpoint

    val tool: McpTool = server.endpoint.mcpTool(schemas, options)

    fun invoke(params: Params): CompletionStage<Any?> = handler(params)

    /**
     * The arguments as the values the handler declared, through the same codecs
     * and refinements an HTTP request goes through — so a tool accepts exactly
     * what the endpoint accepts, and `limit=0` never reaches a handler.
     */
    fun paramsFrom(arguments: JsonObj): Params {
        val path = endpoint.pathSpec.captures.associate { param ->
            val raw = arguments[param.name]?.wireValue()
                ?: throw ApiException(BAD_REQUEST, "'${param.name}' is required and was not supplied.")
            param as ParamKey<*> to param.codec.decode(param.name, raw)
        }
        val queries = endpoint.queries.associate { it as ParamKey<*> to it.valueFrom(arguments[it.name]) }
        // Not arguments: a model asked for a credential invents one. What is
        // supplied here was supplied by whatever serves these tools.
        val headers = endpoint.headerParams.associate { header ->
            header as ParamKey<*> to options.headers[header.name]
                ?.let { header.codec.decode(header.name, it) }
        }
        val body = endpoint.bodyInput?.let { input ->
            val payload = arguments[BODY]
                ?: throw ApiException(BAD_REQUEST, "'$BODY' is required and was not supplied.")
            input as ParamKey<*> to codecs.codec<Any?>(checkNotNull(input.payloadType)).decodeFromString(
                payload.render(),
            )
        }
        return Params(path + queries + headers + listOfNotNull(body), underlying = null, endpoint = endpoint)
    }

    /** A handler's result as the answer a model reads, and as data where a schema binds it. */
    fun resultOf(value: Any?): ToolResult = when (val out: Output<*> = endpoint.output) {
        is DeclaredResponses<*, *> -> when (val outcome = value as Outcome<*, *>) {
            is Outcome.Ok<*> -> success(out.successNamedBy(outcome), outcome.value)
            is Outcome.Err<*> -> failure(out.failureNamedBy(outcome), outcome.error)
        }

        else -> success(out, value)
    }

    private fun success(out: Output<*>, value: Any?): ToolResult {
        val type = out.payloadType ?: return ToolResult("$out, and no payload with it.")
        val text = codecs.codec<Any?>(type).encodeToString(value)
        // Only where the tool published a schema for it: `structuredContent`
        // is validated against that schema, and one sent without it is a
        // client's problem to work out.
        val structured = if (tool.outputSchema == null) null else parseJson(text) as? JsonObj
        return ToolResult(text, structured)
    }

    /**
     * A declared failure, with the description the endpoint gave it — which is
     * the sentence saying what to do differently, and the reason this is not
     * simply the payload.
     */
    private fun failure(declared: ErrorOutput<*>, error: Any?): ToolResult = refused(
        "${declared.status} ${declared.description}: ${codecs.codec<Any?>(declared.type).encodeToString(error)}",
    )
}

/** A query argument as the value the handler declared, or what the endpoint says its absence means. */
private fun QueryParam<*>.valueFrom(argument: JsonValue?): Any? {
    if (listStyle != null) return decodeList(argument.wireValues())
    val raw = argument?.wireValue()
    return when {
        raw != null -> codec.decode(name, raw)
        required -> throw ApiException(BAD_REQUEST, "'$name' is required and was not supplied.")
        else -> default
    }
}

/**
 * A JSON argument as the string a [io.github.matthewjones372.pelican.PlainCodec]
 * reads. A model that sends `7` and one that sends `"7"` are both sending a
 * `userId`, and the codec is the one deciding whether that is a number.
 */
private fun JsonValue.wireValue(): String? = when (this) {
    is JsonStr -> value
    is JsonNum -> value.toString()
    is JsonBool -> value.toString()
    else -> null
}

private fun JsonValue?.wireValues(): List<String> = when (this) {
    null -> emptyList()
    is io.github.matthewjones372.pelican.JsonArr -> items.mapNotNull { it.wireValue() }
    else -> listOfNotNull(wireValue())
}

private fun refused(message: String?) = ToolResult(message ?: "The call was refused.", isError = true)

private fun completed(result: ToolResult): CompletionStage<ToolResult> =
    CompletableFuture.completedStage(result)

private const val BODY = "body"
private const val BAD_REQUEST = 400
