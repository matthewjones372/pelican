package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.JsonObj

/**
 * What an operation answers with: every documented 2xx a declared success, and
 * every other status a failure the handler may return and the caller may match
 * on — one for one with what `endpoint(...)` says.
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

        val byStatus = responses.entries().map { (key, node) -> statusOf(at, key) to node }

        val documented = byStatus.mapNotNull { (status, node) ->
            if (status != null && status in successful) status to node else null
        }
        if (documented.isEmpty()) {
            unsupported(at, "No 2xx response is documented, so nothing says what a successful call returns.")
        }

        val successes = documented.map { (status, node) ->
            val (response, path) = reader.deref(node, at / status.toString())
            success(status, response, path) to headers(response, path)
        }

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
            successes = successes.map { (success, own) -> if (successes.size == 1) success else success.with(own) },
            failures = byStatus
                .filterNot { (status, _) -> status != null && status in successful }
                .map { (status, node) -> failure(status, at / label(status), node) },
            successHeaders = if (successes.size == 1) successes.single().second else emptyList(),
        )
    }

    /**
     * What key this response is written under, as a status. Null is `default`,
     * core's spelling of the one response an endpoint describes and cannot
     * produce — which documents say far more often than they enumerate every
     * status they might answer with.
     */
    private fun statusOf(at: JsonPath, key: String): Int? = when (key) {
        "default" -> null

        else -> key.toIntOrNull()
            ?: unsupported(at / key, "'$key' is not a status code. Pelican declares one status per response.")
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
        if (content.entries().size > 1) return negotiated(status, content, path / "content")

        val (mediaType, node) = single(content, path / "content", "$status response")
        val media = node as? JsonObj
        val schema = media?.get("schema")?.let(::normaliseSchema)

        // OpenAPI 3.2 puts a sequential media type's frame under `itemSchema`,
        // a field it added because `schema` means the whole stream read as an
        // array. Both spellings are read: a document reaching this importer
        // may have been written against either revision, and the frame is what
        // an endpoint description needs either way.
        val item = media?.get("itemSchema")

        return when {
            mediaType.isJson() -> IrSuccess.Json(
                status,
                schema ?: unsupported(path, "The $status response is JSON and declares no schema."),
            )

            mediaType == "application/x-ndjson" -> IrSuccess.Ndjson(
                status,
                item?.let(::normaliseSchema)
                    ?: schema
                    ?: unsupported(path, "The $status NDJSON response declares no schema."),
            )

            mediaType == "text/event-stream" -> IrSuccess.Sse(
                status,
                item?.let { sseFrame(it, status, path) }
                    ?: schema
                    ?: unsupported(path, "The $status event stream declares no schema."),
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

    /**
     * A response documented under several media types: one value written each
     * of those ways, which is what `negotiated(...)` describes.
     *
     * Every entry has to say the same thing about the payload. A content map
     * whose entries disagree describes two responses under one status, and an
     * endpoint answers a negotiated one by writing the value it returned — so
     * there would be no one value to write.
     */
    private fun negotiated(status: Int, content: JsonObj, at: JsonPath): IrSuccess {
        val renderings = content.entries().map { (mediaType, node) ->
            val schema = (node as? JsonObj)?.get("schema")?.let(::normaliseSchema)
                ?: unsupported(at, "The $status response is offered as $mediaType and declares no schema.")

            if (mediaType.isBinary() || schema.isBinary() || mediaType in streamed) {
                unsupported(
                    at,
                    "The $status response is offered as $mediaType among ${content.entries().size} " +
                        "renderings, and $mediaType is bytes or a stream rather than a value written a " +
                        "second way. Document it as the one media type of a response of its own, or " +
                        "exclude the operation.",
                )
            }
            mediaType to schema
        }

        val schemas = renderings.map { it.second }.distinct()
        if (schemas.size > 1) {
            unsupported(
                at,
                "The $status response describes a different schema under each of " +
                    "${renderings.joinToString { it.first }}. `Accept` picks how one value is written and " +
                    "never what it is, so these are two responses: give them different statuses, or " +
                    "document one of them.",
            )
        }

        return IrSuccess.Negotiated(status, schemas.single(), renderings.map { it.first })
    }

    /** Media types that repeat a frame, which a rendering of one value is not. */
    private val streamed = setOf("application/x-ndjson", "text/event-stream")

    /**
     * The payload inside a 3.2 event stream's `itemSchema`.
     *
     * An item of a `text/event-stream` is the event as the SSE parser hands it
     * over — `data`, and possibly `event` and `id` — rather than the payload,
     * and 3.2 points at `contentMediaType` with `contentSchema` for saying what
     * a `data` field carrying JSON holds. `sse<T>` is that `T`, so this is
     * where it is read back out. Anything else is refused rather than guessed
     * at: a `data` described only as a string says nothing about what the
     * stream carries, and there would be no type to name.
     *
     * `event`, `id` and the stream's `retry` do not come back. They say how a
     * service writes its frames rather than what the frames carry, and one of
     * them cannot be written down at all: `sse(id = ...)` is a function of the
     * event value, and a document holds no functions. Emitting an `sse<T>` that
     * claimed to send ids nothing could compute would be worse than dropping
     * them, so the import declares the payload and the service adds the rest.
     */
    private fun sseFrame(item: io.github.matthewjones372.pelican.JsonValue, status: Int, path: JsonPath): JsonObj {
        val data = (item as? JsonObj)?.obj("properties")?.obj("data")
            ?: unsupported(
                path,
                "The $status event stream describes each item with `itemSchema`, and an item of an event " +
                    "stream is the parsed event, so its schema is an object with a `data` property. This one " +
                    "has none.",
            )
        val carried = data["contentSchema"]
            ?: unsupported(
                path,
                "The $status event stream's `data` says it is a string and does not say what is inside it. " +
                    "An `sse<T>` needs the `T`: add `contentMediaType: application/json` and a " +
                    "`contentSchema`, or exclude the operation.",
            )
        return normaliseSchema(carried)
    }

    private fun failure(status: Int?, at: JsonPath, node: io.github.matthewjones372.pelican.JsonValue): IrFailure {
        val (response, path) = reader.deref(node, at)
        val description = response.str("description") ?: reason(status)
        val content = response.obj("content")
        val named = "${label(status)} response"

        if (content == null || content.fields.isEmpty()) {
            return IrFailure(status, null, description, headers(response, path))
        }

        val (mediaType, body) = single(content, path / "content", named)
        if (!mediaType.isJson()) {
            unsupported(
                path / "content",
                "The $named is $mediaType. A declared failure carries a JSON payload, " +
                    "or no payload at all.",
            )
        }
        val schema = (body as? JsonObj)?.get("schema")?.let(::normaliseSchema)

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

    /** What the document calls this response, and what a message about it should call it too. */
    private fun label(status: Int?): String = status?.toString() ?: "default"

    /** What to call a failure the document did not describe. */
    private fun reason(status: Int?): String = when (status) {
        null -> "Any other response"
        else -> reasons[status] ?: "Status $status"
    }

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
