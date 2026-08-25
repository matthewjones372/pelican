// ktlint's `wrapping` rule puts every argument on its own line once a call
// spans more than one, which strands each key above its value and turns this
// file into a staircase. It is a required dependency of `indent`, so it cannot
// be switched off in .editorconfig.
@file:Suppress("ktlint:standard:wrapping")

package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.*

/**
 * Interprets endpoint descriptions as an OpenAPI 3.1.0 document.
 *
 * 3.1 only, deliberately. Nullability is spelled inside a schema rather than
 * around it, so a selectable version would need either an OpenAPI argument on
 * core's [SchemaSource] — a type that exists so core need not know what OpenAPI
 * is — or a second emitter pattern-matching the first one's output. Neither
 * could be faithful: 3.0's `nullable` cannot say "this reference may be null".
 */
fun ApiSpec.openApi(): JsonObj {
    val components = SchemaRegistry()

    // path template -> method -> operation
    val paths = LinkedHashMap<String, MutableMap<String, JsonValue>>()

    // Served but not published, so it reaches the document in no form at all.
    val documented = endpoints.filterNot { it.hidden }

    for (ep in documented) {
        val byMethod = paths.getOrPut(ep.pathSpec.template) { LinkedHashMap() }
        byMethod[ep.method.name.lowercase()] = operation(ep, schemas, components)
    }

    // A webhook says hidden the same way an endpoint does.
    val sent = webhooks.filterNot { it.operation.hidden }

    // Collected from the requirements referencing them, so a scheme cannot be
    // declared unused or used undeclared. A webhook's counts: it is the
    // credential this service presents to a subscriber.
    val schemes = securitySchemesOf(
        security + documented.flatMap { it.security.orEmpty() } +
            sent.flatMap { it.operation.security.orEmpty() },
    )

    return jsonObj {
        "openapi" to "3.1.0"
        // `jsonSchemaDialect` is omitted: 2020-12 is what 3.1 already means,
        // and writing it down is a second place for it to be wrong.
        put("info", jsonObj {
            "title" to title
            "version" to version
            putIfNotNull("description", description)
        })
        if (servers.isNotEmpty()) put("servers", serverList(servers))
        put("paths", JsonObj(paths.mapValues { (_, ops) -> JsonObj(ops.toMap()) }))
        // The order the specification lists the fields in; a document is read
        // by people too.
        if (sent.isNotEmpty()) put("webhooks", webhookItems(sent, schemas, components))
        if (security.isNotEmpty()) put("security", requirements(security))
        put("components", jsonObj {
            put("schemas", components.all())
            if (schemes.isNotEmpty()) {
                put("securitySchemes", jsonObj { schemes.forEach { put(it.name, scheme(it)) } })
            }
        })
    }
}

fun ApiSpec.openApiJson(): String = openApi().renderPretty()

/**
 * A `servers` list, the same Server Object at document level and on an
 * operation. One function, so the two cannot disagree.
 */
private fun serverList(urls: List<String>): JsonArr =
    jsonArr(urls.map { url -> jsonObj { "url" to url } })

/**
 * `webhooks`: the calls the service sends, keyed by name rather than by path.
 * The value is a Path Item Object written by the same [operation] function,
 * since a webhook is one of these read in the other direction.
 */
private fun webhookItems(
    webhooks: List<Webhook>,
    schemas: SchemaSource,
    components: SchemaComponents,
): JsonObj {
    val items = LinkedHashMap<String, MutableMap<String, JsonValue>>()
    webhooks.forEach { hook ->
        items.getOrPut(hook.name) { LinkedHashMap() }[hook.operation.method.name.lowercase()] =
            operation(hook.operation, schemas, components)
    }
    return JsonObj(items.mapValues { (_, ops) -> JsonObj(ops.toMap()) })
}

/** Every declared input that travels outside the body, in the order a reader expects them. */
private fun parameters(ep: Endpoint<*, *>): List<JsonValue> = buildList {
    ep.pathSpec.captures.forEach { p ->
        add(parameter(p.name, "path", true, p.codec, p.description))
    }
    ep.queries.forEach { q ->
        add(parameter(q.name, "query", q.required, q.codec, q.description, q.listStyle))
    }
    ep.headerParams.forEach { h ->
        add(parameter(h.name, "header", h.required, h.codec, h.description, h.listStyle))
    }
    ep.cookieParams.forEach { c ->
        add(parameter(c.name, "cookie", c.required, c.codec, c.description, c.listStyle))
    }
}

/** The request body, or null where the endpoint declares none. */
private fun requestBody(ep: Endpoint<*, *>, schemas: SchemaSource, components: SchemaComponents): JsonObj? =
    when (val body = ep.bodyInput) {
        is JsonBody<*> -> jsonObj {
            "required" to true
            putIfNotNull("description", body.description)
            put("content", jsonObj {
                put("application/json", jsonObj {
                    put("schema", schemas.schema(body.type, components))
                })
            })
        }

        // The same schema a JSON body would publish, because the form is
        // decoded against it — anything else would describe an impossible
        // decode.
        is FormBody<*> -> jsonObj {
            "required" to true
            putIfNotNull("description", body.description)
            put("content", jsonObj {
                put("application/x-www-form-urlencoded", jsonObj {
                    put("schema", schemas.schema(body.type, components))
                })
            })
        }

        is MultipartBody -> jsonObj {
            "required" to true
            putIfNotNull("description", body.description)
            put("content", jsonObj {
                put("multipart/form-data", jsonObj {
                    put("schema", multipartSchema(body))
                    multipartEncoding(body)?.let { put("encoding", it) }
                })
            })
        }

        is RawBody -> jsonObj {
            "required" to true
            putIfNotNull("description", body.description)
            put("content", jsonObj {
                put("application/octet-stream", jsonObj {
                    put("schema", binarySchema("application/octet-stream"))
                })
            })
        }

        // One entry per encoding, all the same schema — one value arriving
        // several ways. Published once and shared, since entries that could
        // drift apart would describe two decodes rather than one.
        is NegotiatedBody<*> -> jsonObj {
            "required" to true
            putIfNotNull("description", body.description)
            val schema = schemas.schema(checkNotNull(body.payloadType), components)
            put("content", jsonObj {
                body.alternatives.forEach { alternative ->
                    put(alternative.mediaType, jsonObj { put("schema", schema) })
                }
            })
        }

        null -> null
    }

/**
 * One entry per successful response the output describes, and one per declared
 * failure — each with its own schema and media type.
 */
private fun responses(ep: Endpoint<*, *>, schemas: SchemaSource, components: SchemaComponents): JsonObj = jsonObj {
    successesOf(ep.output).forEach { out ->
        put(out.status.toString(), jsonObj {
            "description" to successDescription(out)
            responseHeaders(ep.responseHeaders + out.headers)?.let { put("headers", it) }
            val media = out.mediaType
            val schema = schemaOf(out, schemas, components)
            if (media != null && schema != null) {
                put("content", jsonObj { put(media, jsonObj { put("schema", schema) }) })
            }
        })
    }
    ep.errors.forEach { err ->
        // A null status is `default`: a key like any other, except that
        // nothing produces it and it stands for the statuses not enumerated.
        put(err.status?.toString() ?: "default", jsonObj {
            "description" to err.description
            responseHeaders(err.headers)?.let { put("headers", it) }
            val schema = err.type?.let { schemas.schema(it, components) }
            if (schema != null) {
                put("content", jsonObj {
                    put("application/json", jsonObj { put("schema", schema) })
                })
            }
        })
    }
}

/** The successful responses an output describes: several where it names them, else itself. */
private fun successesOf(out: Output<*>): List<Output<*>> =
    if (out is FallibleOutput<*, *>) out.successes else listOf(out)

private fun operation(
    ep: Endpoint<*, *>,
    schemas: SchemaSource,
    components: SchemaComponents,
): JsonObj = jsonObj {
    putIfNotNull("summary", ep.summary)
    putIfNotNull("description", ep.description)
    "operationId" to ep.operationName
    if (ep.tags.isNotEmpty()) put("tags", jsonStrings(ep.tags))
    if (ep.deprecated) "deprecated" to true

    // Null inherits the document-wide requirement; an empty list is
    // deliberately public, which OpenAPI spells as `security: []`.
    ep.security?.let { put("security", requirements(it)) }

    // Where this one operation is served from, in the same shape as the
    // document's own list and from the same function.
    if (ep.servers.isNotEmpty()) put("servers", serverList(ep.servers))

    val params = parameters(ep)
    if (params.isNotEmpty()) put("parameters", jsonArr(params))

    requestBody(ep, schemas, components)?.let { put("requestBody", it) }

    put("responses", responses(ep, schemas, components))
}

/**
 * Reads the payload schema out of an output description. The streaming outputs
 * document the *element* schema — one NDJSON line and one SSE frame each hold
 * a single `T` — except [JsonArrayOutput], which holds all of them.
 */
private fun schemaOf(
    out: Output<*>,
    schemas: SchemaSource,
    components: SchemaComponents,
): JsonObj? = when (out) {
    is JsonOutput<*> -> schemas.schema(out.type, components)

    is NdjsonOutput<*> -> schemas.schema(out.type, components)

    is SseOutput<*> -> schemas.schema(out.type, components)

    is JsonArrayOutput<*> -> jsonObj {
        "type" to "array"
        put("items", schemas.schema(out.type, components))
    }

    is ByteStreamOutput -> binarySchema(out.mediaType)

    is TextOutput -> jsonObj { "type" to "string" }

    is EmptyOutput -> null

    // Failures are documented from ep.errors, so this is the success schema.
    is FallibleOutput<*, *> -> schemaOf(out.success, schemas, components)
}

/**
 * The headers a response carries, as OpenAPI shapes them: a parameter object
 * without `name` and `in`. The same [ResponseHeader] values the handler sets,
 * so a header cannot be documented and not sent, or the reverse.
 */
private fun responseHeaders(headers: List<ResponseHeader<*>>): JsonObj? {
    if (headers.isEmpty()) return null
    return jsonObj {
        headers.forEach { h ->
            put(h.name, jsonObj {
                "required" to h.required
                putIfNotNull("description", h.description ?: h.codec.description)
                putIfNotNull("example", h.codec.example)
                put("schema", h.codec.openApiSchema())
            })
        }
    }
}

/**
 * Opaque bytes, as 3.1's `contentMediaType` rather than 3.0's `format: binary`,
 * which JSON Schema never defined. No `contentEncoding`: these bytes go on the
 * wire as themselves.
 */
private fun binarySchema(mediaType: String) = jsonObj {
    "type" to "string"
    "contentMediaType" to mediaType
}

/**
 * A multipart envelope as OpenAPI models it: one property per part, a file
 * part's `contentMediaType` being what says it is a file.
 *
 * Text parts' schemas come from their own codecs, so a refinement is documented
 * as it would be on a query parameter and Swagger UI refuses to submit a value
 * the server would reject.
 */
private fun multipartSchema(body: MultipartBody): JsonObj = jsonObj {
    "type" to "object"
    put("properties", jsonObj {
        body.parts.forEach { part ->
            when (part) {
                is TextPart<*> -> put(part.name, describedSchema(part.codec.openApiSchema(),
                    part.description ?: part.codec.description,))

                is FilePart<*> -> put(
                    part.name,
                    describedSchema(binarySchema(part.contentType ?: "application/octet-stream"), part.description)
                        .let { schema ->
                            val bound = part.bufferedBytes
                            if (bound == null) schema else schema + jsonObj { "maxLength" to bound }
                        },
                )
            }
        }
    })
    val required = body.parts.filter { (it is TextPart<*> && it.required) || (it is FilePart<*> && it.required) }
    if (required.isNotEmpty()) put("required", jsonStrings(required.map { it.name }))
}

private fun describedSchema(schema: JsonObj, description: String?): JsonObj =
    if (description == null) schema else schema + jsonObj { "description" to description }

/**
 * What each file part is expected to carry, omitted when no part says: OpenAPI
 * already defaults a binary property to `application/octet-stream`.
 */
private fun multipartEncoding(body: MultipartBody): JsonObj? {
    val declared = body.fileParts.mapNotNull { part -> part.contentType?.let { part.name to it } }
    if (declared.isEmpty()) return null
    return jsonObj {
        declared.forEach { (name, contentType) ->
            put(name, jsonObj { "contentType" to contentType })
        }
    }
}

private fun successDescription(out: Output<*>): String = when (out) {
    is NdjsonOutput<*> -> "A newline-delimited JSON stream. Chunked; consume incrementally."
    is JsonArrayOutput<*> -> "A JSON array, chunked; elements are flushed as they are produced."
    is SseOutput<*> -> "A server-sent event stream."
    is ByteStreamOutput -> "A byte stream."
    is EmptyOutput -> "No content."
    is FallibleOutput<*, *> -> successDescription(out.success)
    else -> "Success."
}

/**
 * A parameter's own description wins; a self-documenting codec supplies one
 * everywhere it is used. Refinements ride along in the schema, so the document
 * states the constraint the server enforces.
 */
private fun parameter(
    name: String,
    location: String,
    required: Boolean,
    codec: PlainCodec<*>,
    description: String?,
    listStyle: ListStyle? = null,
): JsonObj = jsonObj {
    "name" to name
    "in" to location
    "required" to required
    putIfNotNull("description", description ?: codec.description)
    // A list's example is an example of its element, and lives in `items`.
    if (listStyle == null) putIfNotNull("example", codec.example)
    listStyle?.let { serialisation(it, location) }
    put("schema", if (listStyle == null) codec.openApiSchema() else listSchema(codec))
}

/**
 * `style` and `explode`, written only where they differ from what OpenAPI
 * assumes at this location — so a reader who meets one can conclude something
 * unusual is being said.
 */
private fun JsonObjBuilder.serialisation(style: ListStyle, location: String) {
    val named = style.styleAt(location)
    if (named != defaultStyleAt(location)) "style" to named
    if (style.explode != defaultExplodeFor(named)) "explode" to style.explode
}

// ------------------------------------------------------------------ security

private fun requirements(reqs: List<SecurityRequirement>): JsonArr =
    jsonArr(reqs.map { req -> jsonObj { put(req.scheme.name, jsonStrings(req.scopes)) } })

private fun scheme(s: SecurityScheme): JsonObj = jsonObj {
    putIfNotNull("description", s.description)
    when (s) {
        is ApiKeyScheme -> {
            "type" to "apiKey"
            "in" to s.location
            "name" to s.paramName
        }

        is HttpScheme -> {
            "type" to "http"
            "scheme" to s.scheme
            putIfNotNull("bearerFormat", s.bearerFormat)
        }

        is OpenIdConnectScheme -> {
            "type" to "openIdConnect"
            "openIdConnectUrl" to s.openIdConnectUrl
        }

        is OAuth2Scheme -> {
            "type" to "oauth2"
            put("flows", jsonObj { s.flows.forEach { put(flowName(it), flow(it)) } })
        }
    }
}

private fun flowName(f: OAuthFlow): String = when (f) {
    is OAuthFlow.AuthorizationCode -> "authorizationCode"
    is OAuthFlow.ClientCredentials -> "clientCredentials"
    is OAuthFlow.Password -> "password"
    is OAuthFlow.Implicit -> "implicit"
}

private fun flow(f: OAuthFlow): JsonObj = jsonObj {
    when (f) {
        is OAuthFlow.AuthorizationCode -> {
            "authorizationUrl" to f.authorizationUrl
            "tokenUrl" to f.tokenUrl
            putIfNotNull("refreshUrl", f.refreshUrl)
        }

        is OAuthFlow.ClientCredentials -> {
            "tokenUrl" to f.tokenUrl
            putIfNotNull("refreshUrl", f.refreshUrl)
        }

        is OAuthFlow.Password -> {
            "tokenUrl" to f.tokenUrl
            putIfNotNull("refreshUrl", f.refreshUrl)
        }

        is OAuthFlow.Implicit -> {
            "authorizationUrl" to f.authorizationUrl
            putIfNotNull("refreshUrl", f.refreshUrl)
        }
    }
    // Always present, even when empty: OpenAPI requires the field, and
    // Swagger UI builds the scope checkboxes from it.
    put("scopes", jsonObj { f.scopes.forEach { (name, granted) -> put(name, JsonStr(granted)) } })
}
