package dev.pelican.importer

import dev.pelican.JsonArr
import dev.pelican.JsonObj
import dev.pelican.JsonStr
import dev.pelican.JsonValue

/**
 * Swagger 2.0, read as the 3.x document it would be today.
 *
 * A separate reader rather than a normalising pass, because 2.0 is a different
 * document: bodies are parameters, media types are lists hanging off the
 * operation, and schemas live under `definitions`. Everything after this point
 * reads one shape, which is what keeps the mapping proper from growing a
 * version check per field.
 *
 * Nothing is decided here that the mapping would decide differently. An
 * operation that `produces` two media types is converted into a response
 * offering two, and refused there, with the message a 3.x document would have
 * got — because it is the same fact about the same operation, and a reader
 * should not get a different answer for having written it in an older dialect.
 */
internal object Swagger2 {

    fun convert(root: JsonObj): JsonObj {
        val document = rewriteRefs(root) as JsonObj
        val consumes = document.strings("consumes").ifEmpty { listOf("application/json") }
        val produces = document.strings("produces").ifEmpty { listOf("application/json") }

        return JsonObj(
            buildMap {
                put("openapi", JsonStr("3.0.3"))
                document["info"]?.let { put("info", it) }
                servers(document)?.let { put("servers", it) }
                document["tags"]?.let { put("tags", it) }
                document["security"]?.let { put("security", it) }
                put("paths", paths(document.obj("paths"), consumes, produces))
                put("components", components(document))
            },
        )
    }

    // ------------------------------------------------------------------ shape

    private fun servers(document: JsonObj): JsonValue? {
        val host = document.str("host")
        val basePath = document.str("basePath").orEmpty()
        val schemes = document.strings("schemes").ifEmpty { if (host == null) emptyList() else listOf("https") }

        val urls = when {
            host == null && basePath.isEmpty() -> return null
            host == null -> listOf(basePath)
            else -> schemes.map { "$it://$host$basePath" }
        }
        return JsonArr(urls.map { jsonObjOf("url" to JsonStr(it)) })
    }

    private fun components(document: JsonObj): JsonObj = JsonObj(
        buildMap {
            document.obj("definitions")?.let { put("schemas", it) }
            document.obj("responses")?.let { put("responses", it) }
            document.obj("securityDefinitions")?.let { put("securitySchemes", securitySchemes(it)) }
        },
    )

    private fun paths(paths: JsonObj?, consumes: List<String>, produces: List<String>): JsonObj = JsonObj(
        paths.entries().associate { (template, rawItem) ->
            val item = rawItem as? JsonObj ?: JsonObj(emptyMap())
            template to JsonObj(
                item.fields.mapValues { (key, value) ->
                    when {
                        key == "parameters" -> JsonArr((value as? JsonArr)?.items.orEmpty().map { parameter(it) })
                        key in methods -> operation(value as? JsonObj ?: JsonObj(emptyMap()), consumes, produces)
                        else -> value
                    }
                },
            )
        },
    )

    private fun operation(operation: JsonObj, consumes: List<String>, produces: List<String>): JsonObj {
        val declared = operation.arr("parameters").map { it as? JsonObj ?: JsonObj(emptyMap()) }
        val body = declared.filter { it.str("in") == "body" }
        val form = declared.filter { it.str("in") == "formData" }
        val rest = declared.filter { it.str("in") != "body" && it.str("in") != "formData" }

        val mediaTypes = operation.strings("consumes").ifEmpty { consumes }
        val answers = operation.strings("produces").ifEmpty { produces }

        return JsonObj(
            buildMap {
                operation.fields.forEach { (key, value) ->
                    if (key !in dropped) put(key, value)
                }
                put("parameters", JsonArr(rest.map { parameter(it) }))
                when {
                    body.isNotEmpty() -> put("requestBody", bodyParameter(body.first(), mediaTypes))
                    form.isNotEmpty() -> put("requestBody", formParameters(form, mediaTypes))
                }
                put("responses", responses(operation.obj("responses"), answers))
            },
        )
    }

    // -------------------------------------------------------------- the parts

    /**
     * A 2.0 parameter wrote its type on itself; a 3.x one writes it in a
     * schema. Everything that describes the value moves inside, and everything
     * that describes the parameter stays out.
     */
    private fun parameter(node: JsonValue): JsonValue {
        val param = node as? JsonObj ?: return node
        if (param["\$ref"] != null || param["schema"] != null) return param

        val schema = JsonObj(param.fields.filterKeys { it in schemaKeys })
        return JsonObj(param.fields.filterKeys { it !in schemaKeys } + mapOf("schema" to schema))
    }

    private fun bodyParameter(body: JsonObj, mediaTypes: List<String>): JsonObj = JsonObj(
        buildMap {
            body.str("description")?.let { put("description", JsonStr(it)) }
            put("required", dev.pelican.JsonBool(body.bool("required")))
            put("content", content(body["schema"] ?: JsonObj(emptyMap()), mediaTypes))
        },
    )

    /**
     * The form fields of a 2.0 operation, as the one body object 3.x models
     * them by. A file field is what decides the media type: `multipart/form-data`
     * is the only one that can carry it.
     */
    private fun formParameters(fields: List<JsonObj>, mediaTypes: List<String>): JsonObj {
        val hasFile = fields.any { it.str("type") == "file" }
        val declared = mediaTypes.filter { it == "multipart/form-data" || it == "application/x-www-form-urlencoded" }
        val mediaType = declared.firstOrNull()
            ?: if (hasFile) "multipart/form-data" else "application/x-www-form-urlencoded"

        val properties = fields.associate { field ->
            val schema = JsonObj(field.fields.filterKeys { it in schemaKeys })
            field.str("name").orEmpty() to if (field.str("type") == "file") binary else schema
        }
        val required = fields.filter { it.bool("required") }.mapNotNull { it.str("name") }

        val schema = JsonObj(
            buildMap {
                put("type", JsonStr("object"))
                put("properties", JsonObj(properties))
                if (required.isNotEmpty()) put("required", JsonArr(required.map { JsonStr(it) }))
            },
        )
        return jsonObjOf("required" to dev.pelican.JsonBool(true), "content" to content(schema, listOf(mediaType)))
    }

    private fun responses(responses: JsonObj?, produces: List<String>): JsonObj = JsonObj(
        responses.entries().associate { (status, rawResponse) ->
            val response = rawResponse as? JsonObj ?: JsonObj(emptyMap())
            if (response["\$ref"] != null) return@associate status to response
            status to JsonObj(
                buildMap {
                    put("description", response["description"] ?: JsonStr(""))
                    response["schema"]?.let { schema ->
                        val body = if ((schema as? JsonObj)?.str("type") == "file") {
                            content(binary, listOf("application/octet-stream"))
                        } else {
                            content(schema, produces)
                        }
                        put("content", body)
                    }
                    response.obj("headers")?.let { put("headers", headers(it)) }
                },
            )
        },
    )

    private fun headers(headers: JsonObj): JsonObj = JsonObj(
        headers.fields.mapValues { (_, rawHeader) ->
            val header = rawHeader as? JsonObj ?: JsonObj(emptyMap())
            JsonObj(
                buildMap {
                    header["description"]?.let { put("description", it) }
                    put("schema", JsonObj(header.fields.filterKeys { it in schemaKeys }))
                },
            )
        },
    )

    private fun content(schema: JsonValue, mediaTypes: List<String>): JsonObj =
        JsonObj(mediaTypes.associateWith { jsonObjOf("schema" to schema) })

    /**
     * 2.0's four flow names, and the two that were renamed. `application`
     * became `clientCredentials` and `accessCode` became `authorizationCode`;
     * the other two kept their names and their fields.
     */
    private fun securitySchemes(schemes: JsonObj): JsonObj = JsonObj(
        schemes.fields.mapValues { (_, rawScheme) ->
            val scheme = rawScheme as? JsonObj ?: JsonObj(emptyMap())
            when (scheme.str("type")) {
                "basic" -> jsonObjOf("type" to JsonStr("http"), "scheme" to JsonStr("basic"))

                "oauth2" -> JsonObj(
                    buildMap {
                        put("type", JsonStr("oauth2"))
                        scheme["description"]?.let { put("description", it) }
                        put("flows", jsonObjOf(flowName(scheme.str("flow")) to flow(scheme)))
                    },
                )

                else -> scheme
            }
        },
    )

    private fun flowName(flow: String?): String = when (flow) {
        "application" -> "clientCredentials"
        "accessCode" -> "authorizationCode"
        else -> flow ?: "implicit"
    }

    private fun flow(scheme: JsonObj): JsonObj = JsonObj(
        buildMap {
            scheme["authorizationUrl"]?.let { put("authorizationUrl", it) }
            scheme["tokenUrl"]?.let { put("tokenUrl", it) }
            put("scopes", scheme.obj("scopes") ?: JsonObj(emptyMap()))
        },
    )

    /** `#/definitions/Order` is `#/components/schemas/Order` in a 3.x document. */
    private fun rewriteRefs(value: JsonValue): JsonValue = when (value) {
        is JsonArr -> JsonArr(value.items.map { rewriteRefs(it) })

        is JsonObj -> JsonObj(
            value.fields.mapValues { (key, field) ->
                val ref = (field as? JsonStr)?.value
                if (key == "\$ref" && ref != null) JsonStr(rewriteRef(ref)) else rewriteRefs(field)
            },
        )

        else -> value
    }

    private fun rewriteRef(ref: String): String = when {
        ref.startsWith("#/definitions/") -> ref.replace("#/definitions/", "#/components/schemas/")
        ref.startsWith("#/parameters/") -> ref.replace("#/parameters/", "#/components/parameters/")
        ref.startsWith("#/responses/") -> ref.replace("#/responses/", "#/components/responses/")
        else -> ref
    }

    private val binary = jsonObjOf("type" to JsonStr("string"), "format" to JsonStr("binary"))

    private val methods = setOf("get", "put", "post", "delete", "options", "head", "patch")

    /** What a 2.0 operation says that a 3.x one says elsewhere or not at all. */
    private val dropped = setOf("parameters", "responses", "consumes", "produces", "schemes")

    /** The fields of a 2.0 parameter that describe the value rather than the parameter. */
    private val schemaKeys = setOf(
        "type", "format", "items", "enum", "default", "maximum", "exclusiveMaximum", "minimum",
        "exclusiveMinimum", "maxLength", "minLength", "pattern", "maxItems", "minItems", "uniqueItems",
        "multipleOf",
    )
}
