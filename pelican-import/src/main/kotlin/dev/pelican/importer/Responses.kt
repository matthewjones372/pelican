package dev.pelican.importer

import dev.pelican.JsonObj

/**
 * What an operation answers with.
 *
 * Every documented 2xx becomes a declared success and every other documented
 * status a failure the handler may return and the caller may match on — which
 * is one for one what `endpoint(...)` can now say, since a handler names the
 * response it is producing.
 *
 * `200 Order` beside `202 Accepted` used to be refused here, for the good
 * reason that an endpoint's output was one type and one status and picking
 * either lost the distinction. It is not refused any more; what is left of that
 * refusal is the pair below, which is genuinely unsayable rather than merely
 * unsaid.
 */
internal class Responses(private val reader: Reader, private val operation: Operation) {

    class Result(
        val successes: List<IrSuccess>,
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

        val documented = byStatus.filter { (status, _) -> status in successful }
        if (documented.isEmpty()) {
            unsupported(at, "No 2xx response is documented, so nothing says what a successful call returns.")
        }

        val successes = documented.map { (status, node) ->
            val (response, path) = reader.deref(node, at / status.toString())
            success(status, response, path) to headers(response, path)
        }

        // A streamed response is produced by handing over the backend's own
        // stream type, and naming one alternative among several is done in
        // core, which cannot name it — so a stream is describable as the one
        // success and not as one of two.
        if (successes.size > 1) {
            val streamed = successes.map { it.first }.filter { it.streams() }
            if (streamed.isNotEmpty()) {
                unsupported(
                    at,
                    "The ${streamed.joinToString { it.status.toString() }} response streams, and this " +
                        "operation documents ${successes.size} successful responses. An endpoint that " +
                        "answers several ways names the one it is producing, and a stream is produced in " +
                        "the server library's own type. Document the stream as the only 2xx, or move the " +
                        "other statuses to an operation of their own.",
                )
            }
        }

        return Result(
            // A header on the only success is the endpoint's own — that is what
            // `emits(...)` says, and it is where a single-response import has
            // always put it. With several, each response carries its own, since
            // that is the only reading under which a `Location` on the 201 stays
            // off the 200.
            successes = successes.map { (success, own) -> if (successes.size == 1) success else success.with(own) },
            failures = byStatus.filterNot { it.first in successful }.map { (code, node) ->
                failure(code, at / code.toString(), node)
            },
            successHeaders = if (successes.size == 1) successes.single().second else emptyList(),
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

        // Both at once is describable: `errorJson<T>(status, ..., retryAfter)`
        // declares the payload and the headers together, and the handler
        // supplies a value for each header when it returns the failure. This
        // used to be refused, and a 429 with a problem body and a `Retry-After`
        // — the commonest failure in any document with rate limiting in it —
        // was the whole of what the refusal cost.
        return IrFailure(status, schema, description, headers(response, path))
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
