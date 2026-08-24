package dev.pelican.importer

import dev.pelican.JsonObj
import dev.pelican.JsonValue

/**
 * The request body.
 *
 * One media type per body, because that is what an endpoint description says:
 * `jsonBody<T>()` is a JSON body, and there is no form of it that means "or
 * XML, if the caller prefers". A document offering two is offering two
 * different decodes of the same request, and picking one here would generate a
 * server that rejects half the callers the document invites.
 */
internal class Bodies(private val reader: Reader, private val operation: Operation) {

    fun read(): IrBody? {
        val raw = operation.node["requestBody"] ?: return null
        val (body, path) = reader.deref(raw, operation.path / "requestBody")
        val description = body.str("description")

        val content = body.obj("content")
            ?: unsupported(path, "The request body declares no content, so nothing says what it carries.")
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
     * A multipart body as its parts, which is how Pelican declares one: the
     * parts are the inputs, and the envelope holding them is assembled from
     * the list rather than written down.
     */
    private fun parts(schema: JsonObj?, encoding: JsonObj?, path: JsonPath): List<IrPart> {
        val properties = schema?.obj("properties")
            ?: unsupported(path, "The multipart body declares no properties, so nothing names its parts.")
        val required = schema.strings("required").toSet()

        val parts = properties.entries().map { (name, rawPart) ->
            val part = normaliseSchema(rawPart)
            val contentType = encoding?.obj(name)?.str("contentType") ?: part.str("contentMediaType")
            if (part.isBinary()) {
                IrPart.File(name, contentType, name in required, part.str("description"))
            } else {
                IrPart.Text(name, part, name in required, part.str("description"))
            }
        }

        // The same rule `endpoint(...)` enforces, stated a build earlier:
        // reading stops at the first file part, because streaming it is the
        // point and a second one could only be reached by buffering the first.
        val files = parts.filterIsInstance<IrPart.File>()
        if (files.size > 1) {
            unsupported(
                path,
                "The multipart body has ${files.size} file parts (${files.joinToString { it.name }}), " +
                    "and only the first could be streamed to a handler.",
            )
        }
        return parts
    }
}

/**
 * The one entry of a `content` map, or a refusal naming what else was there.
 *
 * Shared by bodies and responses because it is the same decision in both
 * places, and a reader who has met the message once should meet the same one.
 */
internal fun single(content: JsonObj, path: JsonPath, what: String): Pair<String, JsonValue> {
    val entries = content.entries()
    return when (entries.size) {
        1 -> entries.single()

        0 -> unsupported(path, "The $what declares no media type.")

        else -> unsupported(
            path,
            "The $what is offered as ${entries.joinToString { it.first }}. An endpoint description " +
                "carries one media type; pick one in the document, or exclude the operation.",
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
