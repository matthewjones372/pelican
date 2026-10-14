package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonValue

/**
 * The request body. Several media types are read as several encodings of one
 * payload, which is the only reading of a content map that survives contact
 * with a handler: `Content-Type` picks the decode.
 */
internal class Bodies(private val reader: Reader, private val operation: Operation) {

    fun read(): IrBody? {
        val raw = operation.node["requestBody"] ?: return null
        val (body, path) = reader.deref(raw, operation.path / "requestBody")
        val description = body.str("description")

        val content = body.obj("content")
            ?: unsupported(path, "The request body declares no content, so nothing says what it carries.")

        if (content.entries().size > 1) return negotiated(content, path, description)

        val (mediaType, node) = single(content, path / "content", "request body")
        val schema = (node as? JsonObj)?.get("schema")

        // 3.2 puts a sequential media type's frame under `itemSchema`, a field
        // it added because `schema` means the whole stream read as an array.
        // Both spellings are read, as `Responses` reads both for a streamed
        // answer: the document may have been written against either revision,
        // and the frame is what `ndjsonIn<T>` needs either way.
        val item = (node as? JsonObj)?.get("itemSchema")

        return when {
            mediaType == "application/x-ndjson" -> IrBody.Ndjson(
                (item ?: schema)?.let(::normaliseSchema)
                    ?: unsupported(path, "The NDJSON request body declares no schema."),
                description,
            )

            mediaType.isJson() -> IrBody.Json(
                schema?.let(::normaliseSchema) ?: unsupported(path, "The JSON request body declares no schema."),
                description,
            )

            mediaType == "application/x-www-form-urlencoded" -> IrBody.Form(
                schema?.let(::normaliseSchema) ?: unsupported(path, "The form request body declares no schema."),
                description,
            )

            mediaType == "multipart/form-data" -> IrBody.Multipart(
                parts(schema as? JsonObj, (node as? JsonObj)?.obj("encoding"), path),
                description,
            )

            mediaType == "application/octet-stream" -> IrBody.Raw(description)

            else -> unsupported(
                path / "content",
                "The request body is $mediaType. Pelican reads JSON, form and multipart bodies, " +
                    "or hands you the raw bytes; there is no description for this one.",
            )
        }
    }

    /**
     * A body offered under several media types, describable in one case only:
     * every entry carries the same schema. Compared after normalisation, so a
     * `$ref` and an inline copy are still one schema, and two real shapes are
     * refused rather than imported as whichever came first.
     */
    private fun negotiated(content: JsonObj, path: JsonPath, description: String?): IrBody {
        val at = path / "content"
        val entries = content.entries().map { (mediaType, node) ->
            mediaType to (node as? JsonObj)?.get("schema")?.let(::normaliseSchema)
        }

        val encodings = entries.map { (mediaType, _) ->
            when {
                mediaType.isJson() -> "application/json"

                mediaType == "application/x-www-form-urlencoded" -> mediaType

                else -> unsupported(
                    at,
                    "The request body is offered as ${entries.joinToString { it.first }}, and $mediaType " +
                        "is not one an endpoint reads a payload from. Pelican reads a body as JSON or as " +
                        "a form; drop the others in the document, or take the body as raw bytes.",
                )
            }
        }

        val distinct = encodings.distinct()
        if (distinct.size != encodings.size) {
            unsupported(
                at,
                "The request body is offered as ${entries.joinToString { it.first }}, which are read the " +
                    "same way — a request carries one Content-Type, so nothing could tell them apart.",
            )
        }

        val schemas = entries.map { it.second }.distinct()
        if (schemas.size > 1) {
            unsupported(
                at,
                "The request body is offered as ${entries.joinToString { it.first }} with a different " +
                    "schema under each. A handler is given one value of one type, so several encodings " +
                    "of one payload is describable and several payloads is not. Where these really are " +
                    "one type, publish one schema under both; where they are alternatives, say so with " +
                    "a `oneOf` and a `discriminator`.",
            )
        }

        val schema = schemas.single()
            ?: unsupported(at, "The request body declares no schema under its media types.")

        return IrBody.Negotiated(schema, encodings, description)
    }

    /**
     * A multipart body as its parts, which is how Pelican declares one.
     */
    private fun parts(schema: JsonObj?, encoding: JsonObj?, path: JsonPath): List<IrPart> {
        val properties = schema?.obj("properties")
            ?: unsupported(path, "The multipart body declares no properties, so nothing names its parts.")
        val required = schema.strings("required").toSet()

        val parts = properties.entries().map { (name, rawPart) ->
            val part = normaliseSchema(rawPart)
            val contentType = encoding?.obj(name)?.str("contentType") ?: part.str("contentMediaType")
            if (part.isBinary()) {
                IrPart.File(
                    name,
                    contentType,
                    name in required,
                    part.str("description"),
                    bufferedBytes = part.long("maxLength"),
                )
            } else {
                IrPart.Text(name, part, name in required, part.str("description"))
            }
        }

        val streamed = parts.filterIsInstance<IrPart.File>().lastOrNull { it.bufferedBytes == null }
        val decided = parts.map {
            if (it !is IrPart.File || it === streamed || it.bufferedBytes != null) it
            else IrPart.File(it.name, it.contentType, it.required, it.description, DEFAULT_BUFFERED_PART_BYTES)
        }

        // Last, because `endpoint(...)` refuses anything declared after a
        // streamed part. A property map has no observable order, so this
        // reorders nothing a reader was relying on.
        return decided.filterNot { it === streamed } + listOfNotNull(streamed)
    }
}

/**
 * What a buffered part is read with when the document says nothing. Large
 * enough for the companion files a second part usually is, small enough that a
 * document from elsewhere cannot authorise a large allocation per request.
 * Written into the generated source, so raising it is a visible edit.
 */
internal const val DEFAULT_BUFFERED_PART_BYTES: Long = 1024L * 1024L

/**
 * The one entry of a `content` map, or a refusal naming what else was there.
 * The two callers that can reach it with several are the ones where several is
 * undescribable: a request body picks its encoding by `Content-Type`, and a
 * declared failure carries a JSON payload or none. A *success* offered several
 * ways is read as a negotiated response instead.
 */
internal fun single(content: JsonObj, path: JsonPath, what: String): Pair<String, JsonValue> {
    val entries = content.entries()
    return when (entries.size) {
        1 -> entries.single()

        0 -> unsupported(path, "The $what declares no media type.")

        else -> unsupported(
            path,
            "The $what is offered as ${entries.joinToString { it.first }}, and nothing here could pick " +
                "between them: a request body is chosen by the `Content-Type` the caller sends, and a " +
                "declared failure carries a JSON payload or none at all. Pick one in the document, or " +
                "exclude the operation.",
        )
    }
}

internal fun String.isJson(): Boolean = this == "application/json" || endsWith("+json")

/**
 * Whether a schema describes opaque bytes. Both spellings: 3.1's
 * `contentMediaType` and 3.0's `format: binary`, which the normaliser leaves
 * alone because this is the only place that has to know about it.
 */
internal fun JsonObj.isBinary(): Boolean =
    str("contentMediaType") != null ||
        (str("type") == "string" && (str("format") == "binary" || str("format") == "byte"))

internal fun String.isBinary(): Boolean =
    this == "application/octet-stream" || startsWith("image/") || startsWith("video/") || startsWith("audio/")
