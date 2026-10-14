package io.github.matthewjones372.pelican.mcp

import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.BodyInput
import io.github.matthewjones372.pelican.ByteStreamOutput
import io.github.matthewjones372.pelican.DeclaredResponses
import io.github.matthewjones372.pelican.EmptyOutput
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.JsonArrayOutput
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonOutput
import io.github.matthewjones372.pelican.MediaOutput
import io.github.matthewjones372.pelican.NdjsonOutput
import io.github.matthewjones372.pelican.NegotiatedOutput
import io.github.matthewjones372.pelican.Output
import io.github.matthewjones372.pelican.PlainCodec
import io.github.matthewjones372.pelican.QueryParam
import io.github.matthewjones372.pelican.SseOutput
import io.github.matthewjones372.pelican.TextOutput
import io.github.matthewjones372.pelican.emptyJsonObj
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.jsonStrings
import io.github.matthewjones372.pelican.listSchema
import io.github.matthewjones372.pelican.openApiSchema
import io.github.matthewjones372.pelican.operationName
import io.github.matthewjones372.pelican.payloadType
import io.github.matthewjones372.pelican.schema.StandaloneSchemas
import kotlin.reflect.KType

/**
 * One endpoint as a model is told about it: what to call it, what to send, and
 * what comes back.
 *
 * This module's own value rather than an SDK's `Tool`, so that deriving a tool
 * list needs no MCP SDK on the classpath. Mapping one of these onto the SDK's
 * type is a handful of lines in whatever serves them.
 */
class McpTool(
    /** [Endpoint.operationName]: what the document and the generated client already call it. */
    val name: String,
    val inputSchema: JsonObj,
    val description: String? = null,
    /** A display name, written only where it would say something the description does not. */
    val title: String? = null,
    /**
     * Null unless one JSON answer declares its type. Publishing one binds the
     * tool to returning `structuredContent` that validates against it.
     */
    val outputSchema: JsonObj? = null,
)

/** Which endpoints a model is told about, and what is supplied on its behalf. */
class McpOptions internal constructor(
    /**
     * Default: everything the document describes. `hidden` already means "still
     * served, not written down", and a tool list is somewhere it is written down.
     *
     * A service with streams or uploads has to narrow this: what MCP cannot
     * carry is refused rather than quietly dropped, so that leaving an endpoint
     * out is a decision somebody made.
     */
    val include: (Endpoint<*, *>) -> Boolean = { !it.hidden },

    /**
     * Header values supplied for every call, by name — an `X-Api-Key` the
     * service requires. Headers are not tool arguments: a model asked for a
     * credential invents one, so the credential belongs to whatever serves
     * these tools.
     */
    val headers: Map<String, String> = emptyMap(),
)

/**
 * What a model is told about, and the defaults above where the block says
 * nothing: every endpoint the document describes, and no headers supplied.
 */
fun mcpOptions(configure: McpOptionsBuilder.() -> Unit = {}): McpOptions =
    McpOptionsBuilder().apply(configure).build()

/** What [mcpOptions]'s block writes into. Each setting is documented on [McpOptions]. */
class McpOptionsBuilder internal constructor() {

    var include: (Endpoint<*, *>) -> Boolean = { !it.hidden }
    var headers: Map<String, String> = emptyMap()

    internal fun build(): McpOptions = McpOptions(include = include, headers = headers)
}

/**
 * The endpoints as tool descriptions, derived from the same values the routes
 * and the document come from.
 *
 * Header and cookie parameters are not arguments: a model asked for an
 * `Authorization` or an `X-Api-Key` invents one. A credential reaches the
 * service from whatever serves these tools, not from the model.
 */
fun ApiSpec.mcpTools(options: McpOptions = mcpOptions()): List<McpTool> {
    val standalone = StandaloneSchemas(schemas)
    return endpoints.filter(options.include).map { it.mcpTool(standalone, options) }
}

internal fun Endpoint<*, *>.mcpTool(schemas: StandaloneSchemas, options: McpOptions): McpTool {
    refuseWhatMcpCannotCarry(options)
    val summary = summary
    val description = description ?: summary
    return McpTool(
        name = operationName,
        inputSchema = inputSchema(schemas),
        description = description,
        title = summary?.takeIf { it != description },
        outputSchema = successType()?.let { schemas.schema(it) },
    )
}

/**
 * One tool as `tools/list` publishes it, which is also the shape a golden file
 * records: a tool list is a contract with whatever is pointed at it, and a
 * schema that quietly narrows is a caller's call that quietly stops working.
 */
fun McpTool.toJson(): JsonObj = jsonObj {
    "name" to name
    putIfNotNull("title", title)
    putIfNotNull("description", description)
    put("inputSchema", inputSchema)
    put("outputSchema", outputSchema)
}

/**
 * Path and query parameters as named arguments, and the body under one `body`
 * property.
 *
 * Nested rather than flattened beside them: a body property called `limit`
 * would otherwise collide with the query parameter of that name, and one
 * argument that is plainly the payload is easier for a model to fill than a
 * flat bag that is sometimes one and sometimes the other.
 */
private fun Endpoint<*, *>.inputSchema(schemas: StandaloneSchemas): JsonObj {
    // Null where no codec reads the body: a multipart envelope and a raw
    // stream have no JSON shape a model could write. Those endpoints are
    // refused outright once there is a dispatch to refuse them for.
    val body = bodyInput?.payloadType?.let { schemas.schema(it) }
    val properties = pathSpec.captures.map { it.name to it.codec.described(it.description) } +
        queries.map { it.name to it.argumentSchema() } +
        listOfNotNull(body?.let { BODY to it.withoutDefs() })
    val required = pathSpec.captures.map { it.name } +
        queries.filter { it.required }.map { it.name } +
        listOfNotNull(BODY.takeIf { body != null })

    return jsonObj {
        "type" to "object"
        put("properties", JsonObj(properties.toMap()))
        if (required.isNotEmpty()) put("required", jsonStrings(required))
        // The body's own types, hoisted to where the pointer to them resolves.
        body?.definitions()?.takeIf { !it.isEmpty }?.let { put(DEFS, it) }
    }
}

/** A multi-valued parameter is an array here exactly as it is in the document. */
private fun QueryParam<*>.argumentSchema(): JsonObj =
    (if (listStyle == null) codec.openApiSchema() else listSchema(codec))
        .let { schema -> description?.let { schema + jsonObj { "description" to it } } ?: schema }

private fun PlainCodec<*>.described(description: String?): JsonObj =
    openApiSchema() + ((description ?: this.description)?.let { jsonObj { "description" to it } } ?: emptyJsonObj)

/**
 * What a tool call cannot carry, refused where the tools are derived rather
 * than at the call that trips over it.
 *
 * MCP answers one call with one result: there is nowhere to put a second row,
 * an event or a byte stream, and nothing on the way in to carry an upload. A
 * cookie is a browser's, and a required header a model would have to invent.
 * Each of these is an endpoint to leave out of `include`, or a decision to
 * make — never a tool that half works.
 */
private fun Endpoint<*, *>.refuseWhatMcpCannotCarry(options: McpOptions) {
    require(output.isOneAnswer()) {
        "$operationName answers with $output, and a tool call has one result to put an answer in — " +
            "a stream of rows or events has nowhere to go. Leave it out with " +
            "mcpOptions { include = { ... } }, and let a caller that can stream have it over HTTP."
    }
    require(output.isJsonAnswer()) {
        "$operationName answers as $output, and a tool result is JSON — there is nothing for a " +
            "rendering of another media type to travel in. Offer a JSON rendering beside it with " +
            "negotiated(json<T>(200), ...), or leave it out with mcpOptions { include = { ... } }."
    }
    require(bodyInput.isReadable()) {
        "$operationName takes $bodyInput, which is bytes rather than a payload a model could write. " +
            "Leave it out with mcpOptions { include = { ... } }."
    }
    require(cookieParams.isEmpty()) {
        "$operationName reads the cookie(s) ${cookieParams.joinToString { it.name }}, and a tool call has " +
            "no browser behind it. Leave it out with mcpOptions { include = { ... } }, or read the value " +
            "from somewhere a caller without cookies can supply it."
    }
    val missing = headerParams.filter { it.required && it.name !in options.headers }
    require(missing.isEmpty()) {
        "$operationName requires the header(s) ${missing.joinToString { it.name }}, and a header is not a " +
            "tool argument — a model asked for one invents it. Supply the value with " +
            "mcpOptions { headers = mapOf(\"${missing.first().name}\" to ...) }, or leave the endpoint out " +
            "with mcpOptions { include = { ... } }."
    }
}

/** One result, and one thing to put in it. */
private fun Output<*>.isOneAnswer(): Boolean = when (this) {
    is JsonOutput<*>, is TextOutput, is EmptyOutput, is MediaOutput<*>, is NegotiatedOutput<*> -> true
    is NdjsonOutput<*>, is SseOutput<*>, is JsonArrayOutput<*>, is ByteStreamOutput -> false
    is DeclaredResponses<*, *> -> successes.all { it.isOneAnswer() }
}

/**
 * Whether that one answer is one a tool result can carry. MCP moves JSON, so a
 * response rendered as something else has nothing to travel in — and a
 * negotiated one travels as the JSON rendering it offers.
 */
private fun Output<*>.isJsonAnswer(): Boolean = when (this) {
    is MediaOutput<*> -> false

    is NegotiatedOutput<*> -> alternatives.any { it is JsonOutput<*> }

    is DeclaredResponses<*, *> -> successes.all { it.isJsonAnswer() }

    is JsonOutput<*>, is TextOutput, is EmptyOutput,
    is NdjsonOutput<*>, is SseOutput<*>, is JsonArrayOutput<*>, is ByteStreamOutput,
    -> true
}

/** A body a codec reads is one a model could write; an envelope or a raw stream is not. */
private fun BodyInput<*>?.isReadable(): Boolean = this == null || payloadType != null

/**
 * The type of the one JSON answer, or null where there is not exactly one.
 *
 * Two successes carry two shapes and MCP has one `outputSchema`; a stream has
 * no single payload at all. Publishing nothing leaves the text content, which
 * no schema binds — publishing a wrong one binds the tool to it.
 */
private fun Endpoint<*, *>.successType(): KType? = when (val out: Output<*> = output) {
    is JsonOutput<*> -> out.type

    // The JSON rendering is the one a tool result carries, so its schema is
    // the one that binds.
    is NegotiatedOutput<*> -> (out.alternatives.firstOrNull { it is JsonOutput<*> } as? JsonOutput<*>)?.type

    is DeclaredResponses<*, *> -> when (val only = out.successes.singleOrNull()) {
        is JsonOutput<*> -> only.type
        is NegotiatedOutput<*> -> (only.alternatives.firstOrNull { it is JsonOutput<*> } as? JsonOutput<*>)?.type
        else -> null
    }

    else -> null
}

/** The pointer a standalone document opens with, without the definitions under it. */
private fun JsonObj.withoutDefs(): JsonObj = JsonObj(fields - DEFS)

private fun JsonObj.definitions(): JsonObj = this[DEFS] as? JsonObj ?: emptyJsonObj

private const val BODY = "body"
private const val DEFS = "\$defs"
