package dev.pelican.importer

import dev.pelican.JsonObj

/**
 * What an operation answers with.
 *
 * An endpoint has one success and a list of declared failures, so that is what
 * the responses have to become: the lowest documented 2xx is the success, and
 * every other documented status is a failure the handler may return and the
 * caller may match on.
 *
 * Two 2xx responses are refused rather than resolved. `200 Order` beside
 * `202 Accepted` is a real distinction the handler makes at runtime, and an
 * endpoint's output type is one thing — picking either one would generate a
 * handler that cannot say what the document says it says.
 */
internal class Responses(private val reader: Reader, private val operation: Operation) {

    class Result(
        val success: IrSuccess,
        val failures: List<IrFailure>,
        val successHeaders: List<IrResponseHeader>,
    )

    fun read(): Result {
        val at = operation.path / "responses"
        val responses = operation.node.obj("responses")
            ?: unsupported(at, "This operation documents no responses at all.")

        val byStatus = responses.entries().map { (key, node) ->
            if (key == "default") {
                unsupported(
                    at / key,
                    "A `default` response says \"and anything else\", and an endpoint declares the " +
                        "statuses it answers with by name. Document the statuses it really returns.",
                )
            }
            val status = key.toIntOrNull()
                ?: unsupported(at / key, "'$key' is not a status code. Pelican declares one status per response.")
            status to node
        }

        val successes = byStatus.filter { (status, _) -> status in successful }
        if (successes.isEmpty()) {
            unsupported(at, "No 2xx response is documented, so nothing says what a successful call returns.")
        }
        if (successes.size > 1) {
            unsupported(
                at,
                "Two successful responses are documented (${successes.joinToString { it.first.toString() }}). " +
                    "An endpoint's output is one type and one status.",
            )
        }

        val (status, rawSuccess) = successes.single()
        val (success, successPath) = reader.deref(rawSuccess, at / status.toString())

        return Result(
            success = success(status, success, successPath),
            failures = byStatus.filterNot { it.first in successful }.map { (code, node) ->
                failure(code, at / code.toString(), node)
            },
            successHeaders = headers(success, successPath),
        )
    }

    /**
     * A JSON array is read as one document rather than as a stream. Pelican
     * can describe either, and they document identically — `type: array` — so
     * the document does not say which it is. The one that reads a response
     * whole is the safe half of that guess: a handler returning a list works
     * against a streaming caller, and a streaming handler against a caller
     * expecting a whole document does not.
     */
    @Suppress("CyclomaticComplexMethod") // One branch per media type; the list is the mapping.
    private fun success(status: Int, response: JsonObj, path: JsonPath): IrSuccess {
        val content = response.obj("content")
        if (content == null || content.fields.isEmpty()) return IrSuccess.Empty(status)

        val (mediaType, node) = single(content, path / "content", "$status response")
        val schema = (node as? JsonObj)?.get("schema")?.let(::normaliseSchema)

        return when {
            mediaType.isJson() -> IrSuccess.Json(
                status,
                schema ?: unsupported(path, "The $status response is JSON and declares no schema."),
            )

            mediaType == "application/x-ndjson" -> IrSuccess.Ndjson(
                status,
                schema ?: unsupported(path, "The $status NDJSON response declares no schema."),
            )

            mediaType == "text/event-stream" -> IrSuccess.Sse(
                status,
                schema ?: unsupported(path, "The $status event stream declares no schema."),
            )

            mediaType == "text/plain" -> {
                if (schema != null && schema.str("type") != "string") {
                    unsupported(path, "The $status response is text/plain but its schema is not a string.")
                }
                IrSuccess.Text(status)
            }

            mediaType.isBinary() -> IrSuccess.Bytes(status, mediaType)

            else -> unsupported(
                path / "content",
                "The $status response is $mediaType, and there is no output that describes it.",
            )
        }
    }

    private fun failure(status: Int, at: JsonPath, node: dev.pelican.JsonValue): IrFailure {
        val (response, path) = reader.deref(node, at)
        val description = response.str("description") ?: reason(status)
        val content = response.obj("content")

        if (content == null || content.fields.isEmpty()) {
            return IrFailure(status, null, description, headers(response, path))
        }

        val (mediaType, body) = single(content, path / "content", "$status response")
        if (!mediaType.isJson()) {
            unsupported(
                path / "content",
                "The $status response is $mediaType. A declared failure carries a JSON payload, " +
                    "or no payload at all.",
            )
        }
        val schema = (body as? JsonObj)?.get("schema")?.let(::normaliseSchema)
        val headers = headers(response, path)

        // A declared failure is a value the handler returns, and that value is
        // a payload and nothing else: `errorJson<T>(...)` has nowhere to put a
        // `Retry-After`. A failure with headers and no body is fine — that one
        // is documented rather than returned — so this is only the pair.
        if (schema != null && headers.isNotEmpty()) {
            unsupported(
                path / "headers",
                "The $status response carries both a body and the header(s) " +
                    "${headers.joinToString { it.name }}. A declared failure carries one payload; " +
                    "a documented failure with no payload can carry headers.",
            )
        }
        return IrFailure(status, schema, description, headers)
    }

    private fun headers(response: JsonObj, path: JsonPath): List<IrResponseHeader> =
        response.obj("headers").entries().map { (name, node) ->
            val (header, at) = reader.deref(node, path / "headers" / name)
            IrResponseHeader(
                name = name,
                schema = header["schema"]?.let(::normaliseSchema)
                    ?: unsupported(at, "Response header '$name' declares no schema."),
                description = header.str("description"),
                required = header.bool("required"),
                example = header.str("example"),
            )
        }

    /** The statuses that mean the call worked, which is the whole of what 2xx means. */
    private val successful = 200..299

    /** What to call a failure the document did not describe. */
    private fun reason(status: Int): String = reasons[status] ?: "Status $status"

    private val reasons = mapOf(
        400 to "Bad request",
        401 to "Unauthorized",
        403 to "Forbidden",
        404 to "Not found",
        409 to "Conflict",
        410 to "Gone",
        422 to "Unprocessable content",
        429 to "Too many requests",
        500 to "Internal server error",
        503 to "Service unavailable",
    )
}
