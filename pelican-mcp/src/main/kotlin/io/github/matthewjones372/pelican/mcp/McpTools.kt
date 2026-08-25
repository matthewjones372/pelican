package io.github.matthewjones372.pelican.mcp

import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.FallibleOutput
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonOutput
import io.github.matthewjones372.pelican.Output
import io.github.matthewjones372.pelican.PlainCodec
import io.github.matthewjones372.pelican.QueryParam
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

/** Which endpoints a model is told about. */
class McpOptions(
    /**
     * Default: everything the document describes. `hidden` already means "still
     * served, not written down", and a tool list is somewhere it is written down.
     */
    val include: (Endpoint<*, *>) -> Boolean = { !it.hidden },
)

/**
 * The endpoints as tool descriptions, derived from the same values the routes
 * and the document come from.
 *
 * Header and cookie parameters are not arguments: a model asked for an
 * `Authorization` or an `X-Api-Key` invents one. A credential reaches the
 * service from whatever serves these tools, not from the model.
 */
fun ApiSpec.mcpTools(options: McpOptions = McpOptions()): List<McpTool> {
    val standalone = StandaloneSchemas(schemas)
    return endpoints.filter(options.include).map { it.mcpTool(standalone) }
}

private fun Endpoint<*, *>.mcpTool(schemas: StandaloneSchemas): McpTool {
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
 * The type of the one JSON answer, or null where there is not exactly one.
 *
 * Two successes carry two shapes and MCP has one `outputSchema`; a stream has
 * no single payload at all. Publishing nothing leaves the text content, which
 * no schema binds — publishing a wrong one binds the tool to it.
 */
private fun Endpoint<*, *>.successType(): KType? = when (val out: Output<*> = output) {
    is JsonOutput<*> -> out.type
    is FallibleOutput<*, *> -> (out.successes.singleOrNull() as? JsonOutput<*>)?.type
    else -> null
}

/** The pointer a standalone document opens with, without the definitions under it. */
private fun JsonObj.withoutDefs(): JsonObj = JsonObj(fields - DEFS)

private fun JsonObj.definitions(): JsonObj = this[DEFS] as? JsonObj ?: emptyJsonObj

private const val BODY = "body"
private const val DEFS = "\$defs"
