// The document is built with `put("key", jsonObj { ... })`, nested several deep.
// ktlint's `wrapping` rule puts every argument on a line of its own once the
// call spans more than one, which strands each key above its value and turns
// the shape of the document into a staircase. It is a required dependency of
// the `indent` rule, so it cannot be switched off in .editorconfig — a file
// that reads as the JSON it emits has to opt out here instead.
@file:Suppress("ktlint:standard:wrapping")

package dev.pelican.openapi

import dev.pelican.*

/**
 * Interprets endpoint descriptions as an OpenAPI 3.1.0 document.
 *
 * This module depends on `pelican-core` and nothing else — no server, no
 * Pekko, and no JSON library. That is the point: documentation is a second
 * reading of the same values, not a byproduct of running the service.
 *
 * Payload schemas are not derived here. They come from the [SchemaSource] the
 * spec was built with, so the same descriptions document identically whether
 * bodies are read by Jackson or by kotlinx.serialization.
 *
 * 3.1 is the only version emitted, and that is a decision rather than an
 * omission. A selectable version would have to reach the schema sources, since
 * nullability is spelled inside a schema and not around it — which means either
 * an OpenAPI version argument on core's [SchemaSource], a type that exists so
 * that documentation can be generated without core knowing what OpenAPI is, or
 * a second pass here rewriting schemas somebody else produced. The second pass
 * is the worse of the two: it would be a second emitter, derived from the first
 * by pattern-matching over its output, and it could not be faithful anyway —
 * 3.0's `nullable` cannot say "this reference may be null", so the two
 * documents would disagree about a fact rather than merely about spelling.
 * One document, from one reading of the values. Consumers still on 3.0-only
 * tooling are not served; the migration note in the reference says so plainly.
 */
fun ApiSpec.openApi(): JsonObj {
    val components = SchemaRegistry()

    // path template -> method -> operation
    val paths = LinkedHashMap<String, MutableMap<String, JsonValue>>()

    // A hidden endpoint is served but not published, so it never reaches the
    // document — not as an empty path, not as a $ref nobody points at.
    val documented = endpoints.filterNot { it.hidden }

    for (ep in documented) {
        val byMethod = paths.getOrPut(ep.pathSpec.template) { LinkedHashMap() }
        byMethod[ep.method.name.lowercase()] = operation(ep, schemas, components)
    }

    // Hidden means unpublished wherever it is written, so a webhook says it the
    // same way an endpoint does.
    val sent = webhooks.filterNot { it.operation.hidden }

    // Schemes are collected from the requirements that reference them, so a
    // scheme cannot be declared and left unused, or used and left undeclared.
    // A webhook's requirement counts: it is the credential this service presents
    // to a subscriber, and a scheme reached only that way is still reached.
    val schemes = securitySchemesOf(
        security + documented.flatMap { it.security.orEmpty() } +
            sent.flatMap { it.operation.security.orEmpty() },
    )

    return jsonObj {
        "openapi" to "3.1.0"
        // `jsonSchemaDialect` is left out on purpose: 2020-12 is what 3.1
        // already means by default, and writing it down would only be a second
        // place for it to be wrong.
        put("info", jsonObj {
            "title" to title
            "version" to version
            putIfNotNull("description", description)
        })
        if (servers.isNotEmpty()) put("servers", serverList(servers))
        put("paths", JsonObj(paths.mapValues { (_, ops) -> JsonObj(ops.toMap()) }))
        // After `paths` and before `components`, which is the order the
        // specification lists the fields in — a document is read by people too.
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
 * A `servers` list, which is the same value at the document level and on one
 * operation — OpenAPI's Server Object either way. One function, so the two
 * cannot come to disagree about what a server is written as.
 */
private fun serverList(urls: List<String>): JsonArr =
    jsonArr(urls.map { url -> jsonObj { "url" to url } })

/**
 * `webhooks`: the calls the service sends, keyed by name rather than by path.
 *
 * The value under each name is a Path Item Object, the same object `paths` is
 * made of, and the operation inside it is written by the same [operation]
 * function — a webhook is one of these read in the other direction, and a
 * second emitter for it would be the first one with the arrows reversed.
 *
 * Grouped by name because OpenAPI files the methods of one name together, and
 * two Pelican webhooks sharing a name are two methods of one item. What is
 * missing on purpose is `servers`: 3.1 says nothing about how a webhook relates
 * to the document's, and a Server Object here would be this document naming a
 * host that belongs to whoever subscribed.
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

        // The same schema a JSON body of this type would publish. That is not
        // a shortcut: the form is decoded *against* that schema, so a document
        // saying anything else would be describing a decode that cannot happen.
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

        null -> null
    }

/**
 * One entry per successful response the output describes, and one per declared
 * failure.
 *
 * An endpoint declaring `200 Order` beside `202 Accepted` publishes both, each
 * with its own schema and its own media type — which is exactly what OpenAPI's
 * `responses` map is for, and what made "one 2xx" a limitation of this library
 * rather than of the format.
 *
 * `emits(...)` headers go on every success, being the endpoint's promise; a
 * header declared on one response goes on that one alone.
 *
 * A `defaultResponse(...)` is written under `default` rather than under a
 * status, which is the one entry here that describes a response no handler
 * could produce.
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
        // A null status is `default`, which is a key in this map like any
        // status and unlike one in every other way: nothing produces it, and it
        // stands for the statuses the entries beside it did not enumerate.
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

    // Null inherits the document-wide requirement; an empty list is the
    // endpoint saying it is public, which OpenAPI spells as `security: []`.
    ep.security?.let { put("security", requirements(it)) }

    // Where this one operation is served from, for the endpoint that is not
    // served where the rest of the API is. Written in the same shape as the
    // document's own list, from the same function, because a reader who has
    // learnt one has learnt the other.
    if (ep.servers.isNotEmpty()) put("servers", serverList(ep.servers))

    val params = parameters(ep)
    if (params.isNotEmpty()) put("parameters", jsonArr(params))

    requestBody(ep, schemas, components)?.let { put("requestBody", it) }

    put("responses", responses(ep, schemas, components))
}

/**
 * Reads the payload schema out of an output description.
 *
 * The streaming outputs differ only in how elements reach the wire, so all but
 * [JsonArrayOutput] document the *element* schema: one NDJSON line and one SSE
 * `data:` frame each hold a single `T`. A JSON array holds all of them, so it
 * is the one that documents as an array.
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

    // Declared failures are documented from ep.errors, alongside the success
    // response this wraps — so the schema here is the success one.
    is FallibleOutput<*, *> -> schemaOf(out.success, schemas, components)
}

/**
 * The headers a response carries, in OpenAPI's own shape — which is a parameter
 * object without the `name` and `in`, since the map key is the name and the
 * location is implied.
 *
 * Same [ResponseHeader] values the handler sets through `Params.setHeader`, so
 * a header cannot be documented and not sent, or sent and not documented.
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
 * Opaque bytes.
 *
 * 3.0 had no way to say this except `format: binary`, a format JSON Schema
 * never defined; 3.1 has `contentMediaType`, which is the real keyword for
 * "the string is not a string, it is a document of this type". No
 * `contentEncoding`: these bytes go on the wire as themselves, and claiming
 * `base64` would describe a payload nothing here produces.
 *
 * Naming the media type again, when it is already the key this schema sits
 * under in `content`, is deliberate. 3.1 permits an empty schema there and
 * lets the key carry it — but a schema is a value that gets read on its own,
 * by a generator or by a reader scrolling past the key, and one that says
 * `type: string` and nothing else is a lie in every context but its own.
 *
 * It is a parameter rather than a constant because `bytes(mediaType = ...)`
 * lets an endpoint stream something it can name — an `image/png`, say — and
 * hardcoding `application/octet-stream` here would document every one of them
 * as anonymous bytes.
 */
private fun binarySchema(mediaType: String) = jsonObj {
    "type" to "string"
    "contentMediaType" to mediaType
}

/**
 * A multipart envelope as the object OpenAPI models it: one property per part,
 * with a file part's `contentMediaType` being the whole of what says it is a
 * file. A part that declared what it expects to carry is documented as
 * carrying that; one that did not falls back to opaque bytes.
 *
 * The text parts' schemas come from their own codecs, so a refinement written
 * on a part is documented exactly as the same refinement on a query parameter
 * is — `minLength` and `pattern` reach the form Swagger UI renders, and it
 * refuses to submit a value the server would reject.
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
                    describedSchema(binarySchema(part.contentType ?: "application/octet-stream"), part.description),
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
 * What each file part is expected to carry. Left off entirely when no part
 * says, since OpenAPI's default for a binary property already is
 * `application/octet-stream` and writing it out would be noise.
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
 * A parameter's own description wins; a codec that documents itself — a shared
 * `Email` or `PageSize` type — supplies one everywhere it is used and nothing
 * has to repeat it per endpoint. Refinements ride along in the schema, so the
 * document states the constraint the server enforces.
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
    // A list's example is an example of its element, and travels inside `items`.
    if (listStyle == null) putIfNotNull("example", codec.example)
    listStyle?.let { serialisation(it, location) }
    put("schema", if (listStyle == null) codec.openApiSchema() else listSchema(codec))
}

/**
 * `style` and `explode`, written only where they differ from what OpenAPI
 * already assumes at this location.
 *
 * A repeated query parameter is the default encoding, so the common case
 * writes neither keyword — which matters because a reader who meets one is
 * entitled to conclude something unusual is being said, and a generator that
 * spelled out the default everywhere would spend that signal.
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
    // Always present, even when empty: OpenAPI requires the field, and Swagger
    // UI reads it to build the scope checkboxes in the Authorize dialog.
    put("scopes", jsonObj { f.scopes.forEach { (name, granted) -> put(name, JsonStr(granted)) } })
}
