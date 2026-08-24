package dev.pelican.importer

import dev.pelican.JsonObj
import dev.pelican.JsonValue

/**
 * The request body.
 *
 * Several media types are read as several *encodings of one payload* — which
 * is what an endpoint can now say, and the only reading of a content map that
 * survives contact with a handler. `jsonBody<Order>() or formBody<Order>()` is
 * one `Order` arriving two ways, and the request's `Content-Type` picks the
 * decode.
 *
 * What stays refused is a content map whose entries describe *different
 * shapes*. That is a union of payloads wearing a content map: the handler is
 * given one value of one type, and picking one entry here would generate a
 * server that rejects half the callers the document invites.
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

        return when {
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
     * A body offered under several media types, which is describable in exactly
     * one case: every entry carries the same schema, so what a `Content-Type`
     * selects is a decode rather than a payload.
     *
     * The entries are compared after normalisation, so a document that spells
     * one entry `$ref` and the other inline is still one schema — and one that
     * genuinely describes two shapes is refused here rather than silently
     * imported as whichever entry came first.
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
     * A multipart body as its parts, which is how Pelican declares one: the
     * parts are the inputs, and the envelope holding them is assembled from
     * the list rather than written down.
     *
     * Several file parts used to be refused here, because reading stops at a
     * streamed part and a second could only be reached by holding the first.
     * Holding one is now something a description can *say* — `bufferedFile` —
     * so this decides which parts are held, in two steps.
     *
     * A `maxLength` on a file part means it is held, with that bound. That is
     * what this library's own generator publishes for a `bufferedFile`, so a
     * document it wrote comes back exactly as it went out, bound included.
     *
     * Among the parts left, which is all of them in a document written by
     * anything else, the *last* is the streamed one and the rest are held with
     * [DEFAULT_BUFFERED_PART_BYTES]. Last because that is the only position a
     * streamed part can occupy: everything after it would be read after reading
     * had stopped. The bound is written into the generated source either way,
     * since a part that costs memory should not cost it invisibly.
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

        // Declared last, because `endpoint(...)` refuses a streamed part with
        // anything declared after it: reading stops there, so the declaration
        // would describe an envelope no server could read. A property map has
        // no order a caller can observe, so this reorders nothing a reader of
        // the document was relying on.
        return decided.filterNot { it === streamed } + listOfNotNull(streamed)
    }
}

/**
 * What a buffered part is read with when the document says nothing: one
 * mebibyte. A number had to be picked, and this one is large enough for the
 * companion files a second part usually is — a thumbnail, a signature, a
 * checksum — and small enough that a document arriving from elsewhere cannot
 * quietly authorise a large allocation per request. It is written into the
 * generated source rather than defaulted there, so raising it is an edit to a
 * line someone can see.
 */
internal const val DEFAULT_BUFFERED_PART_BYTES: Long = 1024L * 1024L

/**
 * The one entry of a `content` map, or a refusal naming what else was there.
 *
 * A request body reaches this only when there is a single entry to take: several
 * are read as several encodings of one payload, above. What is left is the
 * response half, where the refusal still stands — a handler produces one value
 * and the endpoint says how it goes out, and nothing here reads `Accept`.
 */
internal fun single(content: JsonObj, path: JsonPath, what: String): Pair<String, JsonValue> {
    val entries = content.entries()
    return when (entries.size) {
        1 -> entries.single()

        0 -> unsupported(path, "The $what declares no media type.")

        else -> unsupported(
            path,
            "The $what is offered as ${entries.joinToString { it.first }}. A response carries one " +
                "payload rendered one way, and nothing here reads `Accept` to choose between two " +
                "renderings of it; pick one in the document, or exclude the operation.",
        )
    }
}

internal fun String.isJson(): Boolean = this == "application/json" || endsWith("+json")

/**
 * Whether a schema describes opaque bytes rather than a value.
 *
 * Both spellings: 3.1's `contentMediaType`, and 3.0's `format: binary`, which
 * was a format JSON Schema never defined and which the normaliser leaves alone
 * because this is the only place that has to know about it.
 */
internal fun JsonObj.isBinary(): Boolean =
    str("contentMediaType") != null ||
        (str("type") == "string" && (str("format") == "binary" || str("format") == "byte"))

internal fun String.isBinary(): Boolean =
    this == "application/octet-stream" || startsWith("image/") || startsWith("video/") || startsWith("audio/")
