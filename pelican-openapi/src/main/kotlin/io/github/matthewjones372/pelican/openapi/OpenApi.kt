// ktlint's `wrapping` rule puts every argument on its own line once a call
// spans more than one, which strands each key above its value and turns this
// file into a staircase. It is a required dependency of `indent`, so it cannot
// be switched off in .editorconfig.
@file:Suppress("ktlint:standard:wrapping")

package io.github.matthewjones372.pelican.openapi

import io.github.matthewjones372.pelican.*

/**
 * Interprets endpoint descriptions as an OpenAPI document, written against
 * [version].
 *
 * The two revisions describe the same endpoints and differ in three places,
 * all of them cases where 3.1 has no vocabulary for something the server
 * actually does:
 *
 * - **The `openapi` field**, `3.1.0` or `3.2.0`.
 * - **Cookie parameters** carry `style: "cookie"` under 3.2. Pelican joins
 *   cookie pairs with `"; "` and passes values through unescaped, which is
 *   what that style means and is not what the `form` both revisions assume at
 *   this location means. See [serialisation].
 * - **NDJSON and SSE responses** put their schema under `itemSchema` under
 *   3.2, which is the field 3.2 added for exactly this and describes one frame
 *   rather than the whole stream. See [successBody].
 *
 * 3.0 is not on the list and will not be. Nullability is spelled inside a
 * schema rather than around it, so writing it would need either an OpenAPI
 * argument on core's [SchemaSource] — a type that exists so core need not know
 * what OpenAPI is — or a second emitter pattern-matching the first one's
 * output. Neither could be faithful: 3.0's `nullable` cannot say "this
 * reference may be null".
 */
fun ApiSpec.openApi(version: OpenApiVersion = OpenApiVersion.V3_1_0): JsonObj {
    val components = SchemaRegistry()

    // path template -> method -> operation
    val paths = LinkedHashMap<String, MutableMap<String, JsonValue>>()

    // Served but not published, so it reaches the document in no form at all.
    val documented = endpoints.filterNot { it.hidden }

    for (ep in documented) {
        val byMethod = paths.getOrPut(ep.pathSpec.template) { LinkedHashMap() }
        byMethod[ep.method.name.lowercase()] = operation(ep, version, schemas, components)
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
        "openapi" to version.field
        // `jsonSchemaDialect` is omitted: 2020-12 is what both revisions
        // already mean, and writing it down is a second place for it to be
        // wrong. `$self`, which 3.2 adds beside it, is omitted for a different
        // reason — it names the document's own retrieval URI, and nothing in a
        // set of endpoint descriptions says where the document gets published.
        put("info", info())
        if (servers.isNotEmpty()) put("servers", serverList(servers))
        put("paths", JsonObj(paths.mapValues { (_, ops) -> JsonObj(ops.toMap()) }))
        // The order the specification lists the fields in; a document is read
        // by people too.
        if (sent.isNotEmpty()) put("webhooks", webhookItems(sent, version, schemas, components))
        if (security.isNotEmpty()) put("security", requirements(security))
        put("components", jsonObj {
            put("schemas", components.all())
            if (schemes.isNotEmpty()) {
                put("securitySchemes", jsonObj { schemes.forEach { put(it.name, scheme(it)) } })
            }
        })
    }
}

fun ApiSpec.openApiJson(version: OpenApiVersion = OpenApiVersion.V3_1_0): String =
    openApi(version).renderPretty()

/**
 * What the document says about the API itself.
 *
 * A function of its own, and not four lines inlined above, because two
 * different things here are called a version: `info.version` is the API's,
 * which the service names and bumps, and the argument to [openApi] is the
 * specification's. Written in one scope the second would quietly shadow the
 * first, and the document would lose a required field without anything
 * complaining.
 */
private fun ApiSpec.info(): JsonObj = jsonObj {
    "title" to title
    "version" to version
    putIfNotNull("description", description)
}

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
    version: OpenApiVersion,
    schemas: SchemaSource,
    components: SchemaComponents,
): JsonObj {
    val items = LinkedHashMap<String, MutableMap<String, JsonValue>>()
    webhooks.forEach { hook ->
        items.getOrPut(hook.name) { LinkedHashMap() }[hook.operation.method.name.lowercase()] =
            operation(hook.operation, version, schemas, components)
    }
    return JsonObj(items.mapValues { (_, ops) -> JsonObj(ops.toMap()) })
}

/** Every declared input that travels outside the body, in the order a reader expects them. */
private fun parameters(ep: Endpoint<*, *>, version: OpenApiVersion): List<JsonValue> = buildList {
    ep.pathSpec.captures.forEach { p ->
        add(parameter(p.name, "path", true, p.codec, p.description, version))
    }
    ep.queries.forEach { q ->
        add(parameter(q.name, "query", q.required, q.codec, q.description, version, q.listStyle, q.default))
    }
    ep.headerParams.forEach { h ->
        add(parameter(h.name, "header", h.required, h.codec, h.description, version, h.listStyle, h.default))
    }
    ep.cookieParams.forEach { c ->
        add(parameter(c.name, "cookie", c.required, c.codec, c.description, version, c.listStyle, c.default))
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
private fun responses(
    ep: Endpoint<*, *>,
    version: OpenApiVersion,
    schemas: SchemaSource,
    components: SchemaComponents,
): JsonObj = jsonObj {
    successesOf(ep.output).forEach { out ->
        put(out.status.toString(), jsonObj {
            "description" to successDescription(out)
            responseHeaders(ep.responseHeaders + out.headers)?.let { put("headers", it) }
            successBody(out, version, schemas, components)?.let { put("content", it) }
        })
    }
    ep.errors.forEach { err ->
        // A null status is `default`: a key like any other, except that
        // nothing produces it and it stands for the statuses not enumerated.
        put(err.status?.toString() ?: "default", jsonObj {
            "description" to err.description
            // The endpoint's own headers ride on a failure too — `setHeader`
            // puts them on whatever response came back — but they are not
            // promised there: a filter that refuses never reaches the handler
            // that would have set one.
            responseHeaders(err.headers, alsoSometimes = ep.responseHeaders)?.let { put("headers", it) }
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

/**
 * The `content` of a successful response: which media type it is, and which
 * field the schema goes under.
 *
 * NDJSON and SSE are what 3.2 calls *sequential media types* — a repeating
 * structure with no header, footer or envelope around it, and both are named
 * in its list of them. It is explicit about the consequence: `schema` "MUST be
 * applied to the complete content", the whole stream read as though the frames
 * were an array, while `itemSchema` "MUST be applied to each item in the
 * stream independently". One frame is the thing an endpoint description knows
 * about, so under 3.2 the document puts it in the field that means it. Under
 * 3.1 there is only `schema`, and the frame's schema goes there — the reading
 * everything generating streamed responses used before `itemSchema` existed,
 * and the one 3.2 now says is the wrong field.
 *
 * A streamed JSON array is not sequential under either revision: it is one
 * document with brackets round it that happens to arrive in pieces. So
 * [JsonArrayOutput] keeps `schema: {"type": "array"}` throughout.
 */
private fun successBody(
    out: Output<*>,
    version: OpenApiVersion,
    schemas: SchemaSource,
    components: SchemaComponents,
): JsonObj? {
    if (out is FallibleOutput<*, *>) return successBody(out.success, version, schemas, components)
    val media = out.mediaType ?: return null
    val schema = schemaOf(out, version, schemas, components) ?: return null
    val field = if (version == OpenApiVersion.V3_2_0 && out.isSequential()) "itemSchema" else "schema"
    return jsonObj { put(media, jsonObj { put(field, schema) }) }
}

/** The outputs whose media type repeats one structure with nothing around it. */
private fun Output<*>.isSequential(): Boolean = this is NdjsonOutput<*> || this is SseOutput<*>

/**
 * One event of a `text/event-stream`, as 3.2 asks for it to be described.
 *
 * An item of an event stream is not the payload. 3.2 requires implementations
 * to "work with event data after it has been parsed according to the
 * `text/event-stream` specification", and what that parse yields is an event
 * with `data`, `event`, `id` and `retry` fields — so saying `itemSchema` is
 * the payload type would describe a stream nobody sends. [SseOutput.frame]
 * writes an `event:` line when the output names one and a `data:` line
 * carrying the payload as the body codec encoded it, and it writes neither
 * `id` nor `retry`; those two are therefore absent here, on the same principle
 * that stops a response header being documented and not sent.
 *
 * `data` is a string, because every SSE field is. What is inside the string is
 * said with `contentMediaType` and `contentSchema`, which is the pair 3.2
 * points at for this exact case: some users of `text/event-stream` put JSON in
 * the `data` field, and those are the keywords for describing it.
 */
private fun sseEventSchema(
    out: SseOutput<*>,
    schemas: SchemaSource,
    components: SchemaComponents,
): JsonObj = jsonObj {
    "type" to "object"
    // In the order the frame writes them, since a document is read by people too.
    put("properties", jsonObj {
        out.eventName?.let { name ->
            put("event", jsonObj {
                "type" to "string"
                "const" to name
            })
        }
        put("data", jsonObj {
            "type" to "string"
            "contentMediaType" to "application/json"
            put("contentSchema", schemas.schema(out.type, components))
        })
    })
    // Every frame carries both, so both are required. A named stream always
    // writes its name; an unnamed one never does.
    put("required", jsonStrings(listOfNotNull(out.eventName?.let { "event" }, "data")))
}

private fun operation(
    ep: Endpoint<*, *>,
    version: OpenApiVersion,
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

    val params = parameters(ep, version)
    if (params.isNotEmpty()) put("parameters", jsonArr(params))

    requestBody(ep, schemas, components)?.let { put("requestBody", it) }

    put("responses", responses(ep, version, schemas, components))
}

/**
 * Reads the payload schema out of an output description. The streaming outputs
 * document one *item* — an NDJSON line, an SSE event — except [JsonArrayOutput],
 * which is a whole array; [successBody] decides which field that item goes
 * under.
 */
private fun schemaOf(
    out: Output<*>,
    version: OpenApiVersion,
    schemas: SchemaSource,
    components: SchemaComponents,
): JsonObj? = when (out) {
    is JsonOutput<*> -> schemas.schema(out.type, components)

    is NdjsonOutput<*> -> schemas.schema(out.type, components)

    // An SSE item is the parsed event rather than the payload, which is a
    // thing only 3.2 gives the vocabulary to say; see [sseEventSchema].
    is SseOutput<*> -> when (version) {
        OpenApiVersion.V3_2_0 -> sseEventSchema(out, schemas, components)
        OpenApiVersion.V3_1_0 -> schemas.schema(out.type, components)
    }

    is JsonArrayOutput<*> -> jsonObj {
        "type" to "array"
        put("items", schemas.schema(out.type, components))
    }

    is ByteStreamOutput -> binarySchema(out.mediaType)

    is TextOutput -> jsonObj { "type" to "string" }

    is EmptyOutput -> null

    // Failures are documented from ep.errors, so this is the success schema.
    is FallibleOutput<*, *> -> schemaOf(out.success, version, schemas, components)
}

/**
 * The headers a response carries, as OpenAPI shapes them: a parameter object
 * without `name` and `in`. The same [ResponseHeader] values the handler sets,
 * so a header cannot be documented and not sent, or the reverse.
 */
private fun responseHeaders(
    headers: List<ResponseHeader<*>>,
    alsoSometimes: List<ResponseHeader<*>> = emptyList(),
): JsonObj? {
    if (headers.isEmpty() && alsoSometimes.isEmpty()) return null
    val sometimes = alsoSometimes.filterNot { extra -> headers.any { it === extra } }
    return jsonObj {
        (headers + sometimes).forEach { h ->
            put(h.name, jsonObj {
                "required" to (h.required && h !in sometimes)
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

private fun successDescription(out: Output<*>): String = out.description ?: impliedDescription(out)

/** What the media type says when the response does not say for itself. */
private fun impliedDescription(out: Output<*>): String = when (out) {
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
@Suppress("LongParameterList") // One declaration's facets; they travel together.
private fun parameter(
    name: String,
    location: String,
    required: Boolean,
    codec: PlainCodec<*>,
    description: String?,
    version: OpenApiVersion,
    listStyle: ListStyle? = null,
    default: Any? = null,
): JsonObj = jsonObj {
    "name" to name
    "in" to location
    "required" to required
    putIfNotNull("description", description ?: codec.description)
    // A list's example is an example of its element, and lives in `items`.
    if (listStyle == null) putIfNotNull("example", codec.example)
    serialisation(listStyle, location, version)
    // What the server puts in when the caller leaves it out. It reaches the
    // document through the same codec the wire does, so the two spell it alike
    // — `AGENTS.md` asks that of a refinement, and a default is the same claim
    // pointing the other way.
    val schema = if (listStyle == null) codec.openApiSchema() else listSchema(codec)
    put("schema", if (default == null) schema else schema + jsonObj { put("default", defaultOf(codec, default)) })
}

/**
 * A default as the JSON type its own schema describes: `50` under
 * `type: integer`, `"en"` under `type: string`. A number written as a string
 * would fail the schema it sits in.
 */
private fun defaultOf(codec: PlainCodec<*>, value: Any): JsonValue {
    @Suppress("UNCHECKED_CAST")
    val encoded = (codec as PlainCodec<Any>).encode(value)
    return when (codec.openApiType) {
        "integer" -> encoded.toLongOrNull()?.let { JsonNum(it) } ?: JsonStr(encoded)
        "number" -> encoded.toDoubleOrNull()?.let { JsonNum(it) } ?: JsonStr(encoded)
        "boolean" -> encoded.toBooleanStrictOrNull()?.let { JsonBool(it) } ?: JsonStr(encoded)
        else -> JsonStr(encoded)
    }
}

/**
 * `style` and `explode`, written only where they differ from what OpenAPI
 * assumes at this location — so a reader who meets one can conclude something
 * unusual is being said.
 *
 * A cookie is the exception under 3.2, where the style is written whether or
 * not the parameter carries a list. `Cookies.render` joins pairs with `"; "`
 * and passes values through exactly as they were given, refusing the
 * characters RFC 6265 excludes rather than escaping them — which is what 3.2
 * defines `style: "cookie"` to mean, and is not what the `form` that both
 * revisions still assume at this location means. Appendix D says so directly:
 * `form`'s default `explode: true` "uses the wrong delimiter for cookies (`&`
 * instead of `;` followed by a single space)", and `form` percent-encodes
 * where `cookie` does not. 3.1 defines no `cookie` style, so under 3.1 the
 * document says the nearest thing available to it and a reader has to know
 * that cookies are not really `form`.
 */
private fun JsonObjBuilder.serialisation(style: ListStyle?, location: String, version: OpenApiVersion) {
    if (location == "cookie" && version == OpenApiVersion.V3_2_0) {
        "style" to "cookie"
        // Nothing follows it. `explode` defaults to true for `cookie` exactly
        // as it does for `form`, and `repeated()` is the only list a cookie
        // can carry, so the default is always the truth here.
        return
    }
    if (style == null) return
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
