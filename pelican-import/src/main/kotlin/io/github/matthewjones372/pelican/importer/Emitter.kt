package io.github.matthewjones372.pelican.importer

import io.github.matthewjones372.pelican.JsonBool
import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.ListStyle
import io.github.matthewjones372.pelican.codegen.KotlinTypes
import io.github.matthewjones372.pelican.codegen.kdoc
import io.github.matthewjones372.pelican.codegen.kotlinString
import io.github.matthewjones372.pelican.codegen.memberName
import io.github.matthewjones372.pelican.codegen.typeName
import io.github.matthewjones372.pelican.codegen.unique

/**
 * Endpoint descriptions, written out as Kotlin.
 *
 * Meant to be read as well as compiled: inputs as named values at the top,
 * payload types under them, one `endpoint(...)` per operation in document
 * order — the file somebody would have written by hand.
 *
 * Declaration order is load-bearing: top-level values initialise in source
 * order, so an endpoint naming a failure declared below it would read a null
 * at class-init.
 */
internal class Emitter(private val api: IrApi, private val options: ImportOptions) {

    private val types = KotlinTypes(options.codec)
    private val taken = mutableSetOf<String>()

    /** Declaration text, in the order it will be written. */
    private val schemes = LinkedHashMap<String, String>()
    private val inputs = LinkedHashMap<String, String>()
    private val headers = LinkedHashMap<String, String>()
    private val failures = LinkedHashMap<String, String>()

    /** What a repeated declaration is already called: same key, same value. */
    private val named = LinkedHashMap<String, String>()

    /** Anything the generated file needs that `io.github.matthewjones372.pelican.*` does not carry. */
    private val imports = sortedSetOf("kotlin.reflect.KClass", "kotlin.reflect.KType")

    /**
     * Schemas the document wrote out where they were used, under the name the
     * type generator gave each one.
     *
     * The generated schema source needs them: a response whose schema was
     * written inline has no name in `components` to look up, and re-deriving
     * it from the Kotlin class is the thing being avoided.
     */
    private val inlineSchemas = LinkedHashMap<String, JsonObj>()

    /**
     * The Kotlin type a schema becomes, remembering the ones the document did
     * not name. Every path to the type generator goes through here, so nothing
     * can acquire a name the generated schema source has not heard of.
     */
    private fun typeFor(schema: JsonObj, context: String): String {
        val name = types.type(schema, context)
        if (schema["\$ref"] == null && bareType.matches(name)) inlineSchemas[name] = schema
        return name
    }

    fun emit(): Map<String, String> {
        types.declareAll(api.schemas)
        api.schemes.forEach { scheme ->
            val name = schemeName(scheme)
            schemes[name] = "val $name = ${schemeCall(scheme)}"
        }

        // Before the file is assembled: writing an endpoint is what declares
        // the inputs, failures and payload types it names.
        val endpoints = api.endpoints.map { it to endpoint(it) }
        // After the endpoints, so a payload type shared with a route is named
        // by the route that used it first — the document listed `paths` first
        // too, and a reader comparing the two will look there.
        val webhooks = api.webhooks.map { it to webhook(it) }

        return buildMap {
            put(
                options.name.capitalise() + ENDPOINTS_FILE_SUFFIX,
                endpointsFile(endpoints.map { it.second }, webhooks.map { it.second }),
            )
            options.handlers?.let { put(options.name.capitalise() + HANDLERS_FILE_SUFFIX, handlersFile(it)) }
        }
    }

    // ------------------------------------------------------------------ files

    private fun endpointsFile(endpoints: List<String>, webhooks: List<String>): String = buildString {
        // Written out before the imports it is put under: a sealed hierarchy
        // is the one payload type that needs an annotation, and an annotation
        // is an import.
        val payloads = types.declarations()
        imports += types.imports()

        appendLine(banner)
        appendLine("package ${options.packageName}")
        appendLine()
        appendLine("import io.github.matthewjones372.pelican.*")
        imports.forEach { appendLine("import $it") }
        appendLine()
        section("security", schemes.values)
        section("inputs", inputs.values)
        section("response headers", headers.values)
        section("failures", failures.values)
        section("payloads", payloads)
        section("the payload schemas", listOf(schemaSource()))
        section("endpoints", endpoints)
        section("webhooks", webhooks)

        appendLine(rule("the api"))
        appendLine()
        appendLine(kdoc("Every endpoint this document described, in the order it described them.", ""))
        appendLine("val ${memberName(options.name)}Endpoints: List<Endpoint<*, *>> = listOf(")
        api.endpoints.forEach { appendLine("    ${endpointName(it)},") }
        appendLine(")")
        appendLine()
        if (api.webhooks.isNotEmpty()) {
            appendLine(
                kdoc(
                    """
                        Every webhook this document declared: the calls the service sends.

                        Described here and routed nowhere. A webhook goes to a URL a subscriber
                        registered, so there is no path on this side for one to answer at — which
                        is why they are a list of their own rather than more endpoints.
                    """.trimIndent(),
                    "",
                ),
            )
            appendLine("val ${memberName(options.name)}Webhooks: List<Webhook> = listOf(")
            api.webhooks.forEach { appendLine("    ${endpointName(it.operation)},") }
            appendLine(")")
            appendLine()
        }
        appendLine(spec())
    }

    /**
     * The document's own schemas, as a `SchemaSource` in the generated file.
     *
     * It is what makes an imported description self-contained: the spec can be
     * published, and a client generated from it, with no codec module present
     * — and what a caller reads is what the document said rather than a codec's
     * reading of the classes generated from it.
     */
    private fun schemaSource(): String {
        val blob = jsonObjOf(
            "schemas" to api.schemas,
            "names" to JsonObj(
                api.schemas.fields.keys.associate { types.kotlinName(it) to JsonStr(it) },
            ),
            "inline" to JsonObj(inlineSchemas.toMap()),
        )
        return resource("schemas.kt")
            .replace("%NAME%", schemasName)
            .replace("%SPEC%", "${memberName(options.name)}Spec")
            .replace("%DOCUMENT%", chunks(blob.render()))
    }

    /** The blob, in pieces small enough to be string constants. */
    private fun chunks(json: String): String = json.chunked(CHUNK)
        .joinToString("\n") { "        ${kotlinString(it)}," }

    private val schemasName get() = typeName(options.name) + "Schemas"

    private fun resource(name: String): String =
        Emitter::class.java.getResourceAsStream(name)
            ?.use { it.reader().readText() }
            ?: error("$name is missing from pelican-import's resources")

    private fun spec(): String = buildString {
        appendLine(
            kdoc(
                """
                    The description half of the API: what `pelican-openapi` reads to write
                    the document back out, and what `pelican-codegen` reads to generate a
                    client.

                    The no-argument form carries the document's own schemas, so it needs
                    no codec module and publishes what the document said. Pass a codec's
                    schema source instead — `%SPEC%(JacksonCodecs)` — to have the payload
                    types described from the Kotlin classes, which is what a service that
                    has since edited them wants.
                """.trimIndent().replace("%SPEC%", "${memberName(options.name)}Spec"),
                "",
            ),
        )
        appendLine("fun ${memberName(options.name)}Spec(): ApiSpec = ${memberName(options.name)}Spec($schemasName)")
        appendLine()
        appendLine("fun ${memberName(options.name)}Spec(schemas: SchemaSource): ApiSpec = ApiSpec(")
        appendLine("    endpoints = ${memberName(options.name)}Endpoints,")
        appendLine("    schemas = schemas,")
        appendLine("    title = ${kotlinString(api.title)},")
        appendLine("    version = ${kotlinString(api.version)},")
        api.description?.let { appendLine("    description = ${kotlinString(it)},") }
        if (api.servers.isNotEmpty()) {
            appendLine("    servers = listOf(${api.servers.joinToString { kotlinString(it) }}),")
        }
        if (api.security.isNotEmpty()) {
            appendLine("    security = listOf(${api.security.joinToString { requirement(it) }}),")
        }
        if (api.webhooks.isNotEmpty()) {
            appendLine("    webhooks = ${memberName(options.name)}Webhooks,")
        }
        append(")")
    }

    /**
     * The handlers, once, as a starting point: every one a `TODO()`, so the
     * service compiles and routes and throws where nothing is written yet.
     * Written once and never overwritten — see [Import.write].
     */
    private fun handlersFile(backend: Backend): String = buildString {
        appendLine(stubBanner)
        appendLine("package ${options.packageName}")
        appendLine()
        appendLine("import io.github.matthewjones372.pelican.*")
        appendLine("import ${backend.packageName}.*")
        appendLine()
        appendLine("val ${memberName(options.name)}Handlers: List<ServerEndpoint> = listOf(")
        api.endpoints.forEach { ep ->
            val body = "{ ${arguments(ep)}TODO(${kotlinString(ep.operationId)}) }"
            appendLine("    ${endpointName(ep)} ${binder(ep)} $body,")
        }
        appendLine(")")
    }

    private fun binder(ep: IrEndpoint): String = when {
        // Several 2xx means the handler names the one it is producing, whether
        // or not any failure is typed — that is the binder taking an `Outcome`,
        // under the name that reads right when the alternatives are successes.
        ep.successes.size > 1 -> "handledOneOf"

        ep.success is IrSuccess.Bytes -> "bytesNow"

        streams(ep) && ep.failures.any { it.returnable } -> "streamedOrFail"

        streams(ep) -> "streamedNow"

        ep.failures.any { it.returnable } -> "handledOrFail"

        ep.success is IrSuccess.Empty -> "handledWith"

        else -> "handledNow"
    }

    /** The first documented 2xx, which for every shape but [binder]'s first branch is the only one. */
    private val IrEndpoint.success: IrSuccess get() = successes.first()

    private fun streams(ep: IrEndpoint) = ep.success is IrSuccess.Ndjson || ep.success is IrSuccess.Sse

    /** The handler's parameter, named after the inputs it decodes. */
    private fun arguments(ep: IrEndpoint): String {
        val keys = keysOf(ep)
        return when {
            keys.isEmpty() || keys.size > TUPLE_LIMIT -> ""
            keys.size == 1 -> "${keys.single()} -> "
            else -> "(${keys.joinToString()}) -> "
        }
    }

    // -------------------------------------------------------------- endpoints

    private fun endpoint(ep: IrEndpoint): String = buildString {
        val keys = keysOf(ep)
        ep.description?.let { appendLine(kdoc(it, "")) }
            ?: ep.summary?.let { appendLine(kdoc(it, "")) }

        appendLine("val ${endpointName(ep)} = ${declaration(keys)} {")
        body(ep, keys).forEach { appendLine("    $it") }
        append("}")
    }

    /**
     * A call the document says the service sends — an endpoint's block minus
     * the URL, since `webhook(...)` takes the method instead. Inputs are
     * declared inside: the tuple form exists to type a handler's parameter, and
     * nothing binds a handler to one of these.
     */
    private fun webhook(hook: IrWebhook): String = buildString {
        val ep = hook.operation
        ep.description?.let { appendLine(kdoc(it, "")) }
            ?: ep.summary?.let { appendLine(kdoc(it, "")) }

        // POST is what `webhook(...)` assumes, so the common case says nothing.
        val method = if (ep.method == "POST") "" else ", method = Method.${ep.method}"
        appendLine("val ${endpointName(ep)} = webhook(${kotlinString(hook.name)}$method) {")
        body(ep, keys = emptyList(), webhook = true).forEach { appendLine("    $it") }
        append("}")
    }

    /** The block's statements, in the order the DSL reads best in. */
    private fun body(ep: IrEndpoint, keys: List<String>, webhook: Boolean = false): List<String> = buildList {
        if (!webhook) add(route(ep))
        // Under the route, because it qualifies it: this is the operation whose
        // path is served somewhere other than the rest of the document.
        if (ep.servers.isNotEmpty()) add("servers(${ep.servers.joinToString { kotlinString(it) }})")
        ep.summary?.let { add("summary = ${kotlinString(it)}") }
        ep.description?.let { add("description = ${kotlinString(it)}") }
        add("operationId = ${kotlinString(ep.operationId)}")
        if (ep.tags.isNotEmpty()) add("tag(${ep.tags.joinToString { kotlinString(it) }})")
        if (ep.deprecated) add("deprecated = true")
        if (webhook || keys.size > TUPLE_LIMIT) addAll(lensInputs(ep))
        addAll(security(ep))
        if (ep.responseHeaders.isNotEmpty()) {
            add("emits(${ep.responseHeaders.joinToString { headerName(it) }})")
        }
        ep.failures.filterNot { it.returnable }.forEach { add(documented(ep, it)) }
        add(output(ep))
    }

    /**
     * `endpoint(a, b)` while the inputs fit in a tuple, and the lens form past
     * that. Six is where core stops having an overload, and it is where a
     * destructured handler stops being readable anyway; past it the handler
     * takes the whole bag and reads it by key.
     */
    private fun declaration(keys: List<String>): String = when {
        keys.isEmpty() -> "endpoint(noInputs)"
        keys.size <= TUPLE_LIMIT -> "endpoint(${keys.joinToString()})"
        else -> "endpoint"
    }

    private fun lensInputs(ep: IrEndpoint): List<String> = buildList {
        listOf("query" to "query", "header" to "header", "cookie" to "cookie").forEach { (location, call) ->
            val declared = ep.params.filter { it.location == location }
            if (declared.isNotEmpty()) add("$call(${declared.joinToString { inputName(it) }})")
        }
        when (val body = ep.body) {
            is IrBody.Multipart -> add("part(${body.parts.joinToString { partName(it) }})")
            null -> Unit
            else -> add("body(${bodyName(ep)})")
        }
    }

    private fun route(ep: IrEndpoint): String {
        val path = pathExpression(ep)
        val method = ep.method.lowercase()
        return if (method in routeMethods) "$method($path)" else "route(Method.${ep.method}, path($path))"
    }

    /**
     * The path as core's `/` operator writes it: literals as strings, captures
     * as the input value that decodes them. An all-literal route stays one
     * string, because `get("orders" / "recent")` says nothing `get("/orders/recent")`
     * does not.
     */
    private fun pathExpression(ep: IrEndpoint): String {
        val segments = ep.path.split('/').filter { it.isNotEmpty() }
        if (segments.none { it.startsWith("{") }) return kotlinString(ep.path)

        val parts = segments.map { segment ->
            if (segment.startsWith("{")) {
                val name = segment.trim('{', '}')
                inputName(ep.params.first { it.location == "path" && it.name == name })
            } else {
                kotlinString(segment)
            }
        }
        // `path(x)` lifts a leading capture, which the `/` operator cannot do
        // on its own: a route is a `PathSpec`, and one capture is not.
        val head = if (segments.first().startsWith("{")) "path(${parts.first()})" else parts.first()
        return (listOf(head) + parts.drop(1)).joinToString(" / ")
    }

    private fun security(ep: IrEndpoint): List<String> = when {
        ep.security == null -> emptyList()

        ep.security.isEmpty() -> listOf("noSecurity()")

        else -> ep.security.map { requirement ->
            val scopes = requirement.scopes.joinToString("") { ", ${kotlinString(it)}" }
            "security(${schemeName(requirement.scheme)}$scopes)"
        }
    }

    private fun requirement(requirement: IrRequirement): String {
        val scopes = requirement.scopes.joinToString { kotlinString(it) }
        return "${schemeName(requirement.scheme)}.requires($scopes)"
    }

    /**
     * A response the handler never returns: one it throws, or the `default`
     * nothing can produce. The `default` keeps its payload type, since a bare
     * `defaultResponse(...)` would turn "any other error is a Problem" back
     * into "any other error".
     */
    private fun documented(ep: IrEndpoint, failure: IrFailure): String {
        val headers = failure.headers.joinToString("") { ", ${headerName(it)}" }
        val description = kotlinString(failure.description)
        val schema = failure.schema

        return when {
            failure.status != null -> "errorResponse(${failure.status}, $description$headers)"

            schema == null -> "defaultResponse($description$headers)"

            else -> {
                val type = typeFor(schema, typeName(ep.operationId) + "Failure")
                "defaultJson<$type>($description$headers)"
            }
        }
    }

    // ---------------------------------------------------------------- outputs

    private fun output(ep: IrEndpoint): String {
        val successes = ep.successes.map { successOutput(ep, it) }
        val success = successes.joinToString(" or ")
        val declared = ep.failures.filter { it.returnable }.map { failureName(ep, it) }
        return when {
            declared.isEmpty() -> success

            // `a or b orFail x` groups left to right, so the infix spelling
            // needs no help; the vararg one is a call on the *last* output
            // unless the alternatives are bracketed first.
            declared.size == 1 -> "$success orFail ${declared.single()}"

            successes.size == 1 -> "$success.orFail(${declared.joinToString()})"

            else -> "($success).orFail(${declared.joinToString()})"
        }
    }

    private fun successOutput(ep: IrEndpoint, success: IrSuccess): String {
        val context = typeName(ep.operationId) + "Response"
        // Headers belonging to this response rather than to the endpoint, which
        // is a list only an operation documenting several 2xx has — see
        // `Responses.read`.
        val headers = success.headers.joinToString("") { ", ${headerName(it)}" }

        // The status is written out whenever headers follow it, default or
        // not: they are a vararg behind it, and `json<T>(location)` would be
        // offering a header where the status goes.
        val arguments = { status: String ->
            when {
                headers.isEmpty() -> status
                status.isEmpty() -> "status = ${success.status}$headers"
                else -> status + headers
            }
        }

        return when (success) {
            is IrSuccess.Json ->
                "json<${typeFor(success.schema, context)}>(${arguments(status(success.status, 200))})"

            is IrSuccess.Ndjson -> "ndjson<${typeFor(success.schema, context)}>(${status(success.status, 200)})"

            is IrSuccess.Sse -> "sse<${typeFor(success.schema, context)}>(${status(success.status, 200)})"

            is IrSuccess.Text -> "text(${arguments(status(success.status, 200))})"

            is IrSuccess.Empty -> "empty(${arguments(status(success.status, 204))})"

            is IrSuccess.Bytes -> {
                val mediaType = if (success.mediaType == OCTET_STREAM) "" else kotlinString(success.mediaType)
                val status = status(success.status, 200)
                "bytes(${listOf(mediaType, status).filter { it.isNotEmpty() }.joinToString()})"
            }
        }
    }

    /** A status the same as the output's own default is left unsaid. */
    private fun status(status: Int, default: Int) = if (status == default) "" else "status = $status"

    // ----------------------------------------------------------------- names

    private fun endpointName(ep: IrEndpoint): String =
        named.getOrPut("endpoint:${ep.operationId}") { unique(memberName(ep.operationId), taken) }

    private fun schemeName(scheme: IrScheme): String = schemeName(scheme.name)

    private fun schemeName(name: String): String = named.getOrPut("scheme:$name") {
        // A scheme called `bearerAuth` and the builder that makes one are the
        // same word, and a value shadowing the function that produced it reads
        // as a mistake even where it compiles.
        val candidate = memberName(name).let { if (it in builders) it + "Scheme" else it }
        unique(candidate, taken)
    }

    private fun headerName(header: IrResponseHeader): String {
        val key = "header:${header.name}:${header.required}:${header.example}:${header.schema.render()}"
        return named.getOrPut(key) {
            val name = unique(memberName(header.name), taken)
            val plain = plain(header.schema, typeName(header.name)).exampled(header.example)
            val declared = if (plain.codec == null) {
                "responseHeader<${plain.type}>(${kotlinString(header.name)}${description(header.description)})"
            } else {
                "responseHeader(${kotlinString(header.name)}, ${plain.codec}${description(header.description)})"
            }
            headers[name] = "val $name = $declared" + if (header.required) "" else ".optional()"
            name
        }
    }

    /** Only ever asked of a [returnable] failure: the two below are what that means. */
    private fun failureName(ep: IrEndpoint, failure: IrFailure): String {
        val schema = failure.schema ?: error("A failure with no payload has no name")
        val status = failure.status ?: error("A default response is not one a handler could name")
        val type = typeFor(schema, typeName(ep.operationId) + "Failure")
        // The headers are part of the key, not just of the declaration: two
        // 404s carrying the same payload are one value, and two 429s that
        // differ in what they send back are not.
        val headers = failure.headers.joinToString("") { ", ${headerName(it)}" }
        val key = "failure:$status:$type:${failure.description}:$headers"
        return named.getOrPut(key) {
            val name = unique(memberName(type + reason(status)), taken)
            failures[name] =
                "val $name = errorJson<$type>($status, ${kotlinString(failure.description)}$headers)"
            name
        }
    }

    private fun keysOf(ep: IrEndpoint): List<String> = buildList {
        val captures = Regex("\\{([^}]+)}").findAll(ep.path).map { it.groupValues[1] }.toList()
        captures.forEach { capture ->
            add(inputName(ep.params.first { it.location == "path" && it.name == capture }))
        }
        listOf("query", "header", "cookie").forEach { location ->
            ep.params.filter { it.location == location }.forEach { add(inputName(it)) }
        }
        when (val body = ep.body) {
            is IrBody.Multipart -> body.parts.forEach { add(partName(it)) }
            null -> Unit
            else -> add(bodyName(ep))
        }
    }

    private fun inputName(param: IrParam): String {
        val declaration = inputDeclaration(param)
        return named.getOrPut("input:${param.location}:${param.name}:$declaration") {
            val name = unique(memberName(param.name), taken)
            inputs[name] = "val $name = $declaration"
            name
        }
    }

    private fun bodyName(ep: IrEndpoint): String {
        val body = ep.body ?: error("No body to name")
        val declaration = bodyDeclaration(ep, body)
        return named.getOrPut("body:$declaration") {
            val name = unique(memberName(typeName(ep.operationId) + "Body"), taken)
            inputs[name] = "val $name = $declaration"
            name
        }
    }

    private fun partName(part: IrPart): String {
        val declaration = partDeclaration(part)
        return named.getOrPut("part:${part.name}:$declaration") {
            val name = unique(memberName(part.name), taken)
            inputs[name] = "val $name = $declaration"
            name
        }
    }

    // ---------------------------------------------------------- declarations

    private fun inputDeclaration(param: IrParam): String {
        val factory = when (param.location) {
            "path" -> "pathParam"
            "query" -> "queryParam"
            "header" -> "headerParam"
            else -> "cookieParam"
        }
        val plain = plain(param.schema, typeName(param.name)).exampled(param.example)
        val declared = if (plain.codec == null) {
            "$factory<${plain.type}>(${kotlinString(param.name)}${description(param.description)})"
        } else {
            "$factory(${kotlinString(param.name)}, ${plain.codec}${description(param.description)})"
        }
        return declared + spread(param) + modifier(param, plain.type)
    }

    /**
     * How several values are told apart, where the parameter carries several.
     *
     * It comes before `optional()` because that is the only order that
     * compiles: spreading turns a `QueryParam<Int>` into a
     * `QueryParam<List<Int>>`, and the modifier below then makes that
     * nullable.
     */
    private fun spread(param: IrParam): String = when (param.listStyle) {
        null -> ""
        ListStyle.REPEATED -> ".repeated()"
        ListStyle.COMMA -> ".commaSeparated()"
        ListStyle.SPACE -> ".spaceSeparated()"
        ListStyle.PIPE -> ".pipeSeparated()"
    }

    /**
     * `optional()` and `default(...)` are how an input says it may be left
     * out, and a path parameter can say neither — a route with a hole in it
     * does not match.
     */
    private fun modifier(param: IrParam, type: String): String = when {
        param.location == "path" || param.required -> ""
        param.default != null -> ".default(${defaultValue(param, type)})"
        else -> ".optional()"
    }

    /** A default is a value of the declared type, so a list's is a list of them. */
    private fun defaultValue(param: IrParam, type: String): String {
        val value = checkNotNull(param.default)
        if (param.listStyle == null) return literal(value, type)
        val items = (value as? io.github.matthewjones372.pelican.JsonArr)?.items.orEmpty()
        return "listOf(" + items.joinToString { literal(it, type) } + ")"
    }

    private fun bodyDeclaration(ep: IrEndpoint, body: IrBody): String {
        val context = typeName(ep.operationId) + "Request"
        return when (body) {
            is IrBody.Json -> "jsonBody<${typeFor(body.schema, context)}>(${describedBy(body.description)})"

            is IrBody.Form -> "formBody<${typeFor(body.schema, context)}>(${describedBy(body.description)})"

            is IrBody.Raw -> "rawBody(${describedBy(body.description)})"

            is IrBody.Multipart -> error("A multipart body is declared by its parts")

            // One payload, one type, and one `or` per further encoding — the
            // same spelling a hand-written description uses, so a reader of the
            // generated file learns the library rather than the importer.
            is IrBody.Negotiated -> negotiatedDeclaration(body, typeFor(body.schema, context))
        }
    }

    private fun partDeclaration(part: IrPart): String = when (part) {
        is IrPart.File -> fileDeclaration(part) + if (part.required) "" else ".optional()"

        is IrPart.Text -> {
            val plain = plain(part.schema, typeName(part.name))
            val declared = if (plain.codec == null) {
                "textPart<${plain.type}>(${kotlinString(part.name)}${description(part.description)})"
            } else {
                "textPart(${kotlinString(part.name)}, ${plain.codec}${description(part.description)})"
            }
            declared + if (part.required) "" else ".optional()"
        }
    }

    // ------------------------------------------------------- values on the wire

    /**
     * A schema for a value travelling as one string, as its Kotlin type and the
     * codec that decodes it.
     *
     * A codec is written only where there is something to say beyond the type —
     * a constraint, or a format core has no reified type for. Otherwise the
     * declaration is `queryParam<Int>("limit")`.
     *
     * Constraints become refinements rather than comments: `atLeast(1)` rejects
     * a zero *and* documents `minimum: 1`.
     */
    private fun plain(schema: JsonObj, context: String): Plain {
        if (schema["enum"] != null) return Plain(typeFor(schema, context), null)

        val format = schema.str("format")
        val type = when (schema.scalarType()) {
            "integer" -> if (format == "int64") "Long" else "Int"
            "number" -> "Double"
            "boolean" -> "Boolean"
            else -> stringType(format)
        }
        type.qualified()?.let { imports += it }

        val refinements = refinements(schema, type)
        val extra = schema.without("default").facets()
        if (refinements.isEmpty() && extra.isEmpty) return Plain(type, null)

        val facets = if (extra.isEmpty) "" else ".withFacets(${json(extra)})"
        return Plain(type, codecs.getValue(type) + facets + refinements.joinToString(""))
    }

    /**
     * The same value, carrying the sample the document offered.
     *
     * An example belongs to the *type* in Pelican rather than to one use of
     * it, which is `describedAs`. Writing it out forces the codec form even
     * where nothing else needed one — a reified `queryParam<Int>("limit")` has
     * nowhere to hang it.
     */
    private fun Plain.exampled(example: String?): Plain {
        if (example == null) return this
        val base = codec ?: codecs.getValue(type)
        return Plain(type, "$base.describedAs(example = ${kotlinString(example)})")
    }

    private fun stringType(format: String?): String = when (format) {
        "uuid" -> "UUID"
        "date" -> "LocalDate"
        "date-time" -> "Instant"
        "uri" -> "URI"
        else -> "String"
    }

    private fun refinements(schema: JsonObj, type: String): List<String> = buildList {
        schema.int("minLength")?.let { add(".minLength($it)") }
        schema.int("maxLength")?.let { add(".maxLength($it)") }
        schema.str("pattern")?.let { add(".matching(Regex(${kotlinString(it)}))") }
        (schema["minimum"] as? JsonNum)?.let { add(".atLeast(${number(it.value, type)})") }
        (schema["maximum"] as? JsonNum)?.let { add(".atMost(${number(it.value, type)})") }
    }

    /**
     * What the schema says that a codec and its refinements do not carry: an
     * unmodelled `format`, a `multipleOf`, a `minItems`. Carried through as
     * facets so the document a generated service publishes still says it —
     * unenforced, exactly as it was unenforced in the document this came from.
     */
    private fun JsonObj.facets(): JsonObj = JsonObj(
        fields.filterKeys { it !in carried }
            .let { kept -> if (str("format") in knownFormats) kept - "format" else kept },
    )

    private fun number(value: Number, type: String) = when (type) {
        "Long" -> "${value}L"
        "Double" -> if ("." in value.toString()) "$value" else "$value.0"
        else -> "$value"
    }

    /** A facet object as the builder that writes it. Values only: no nesting reaches here. */
    private fun json(facets: JsonObj): String {
        val fields = facets.fields.entries.joinToString("; ") { (key, value) ->
            when (value) {
                is JsonStr -> "${kotlinString(key)} to ${kotlinString(value.value)}"
                is JsonNum -> "${kotlinString(key)} to ${value.value}"
                is JsonBool -> "${kotlinString(key)} to ${value.value}"
                else -> "put(${kotlinString(key)}, parseJson(${kotlinString(value.render())}))"
            }
        }
        return "jsonObj { $fields }"
    }

    private fun String.qualified(): String? = when (this) {
        "UUID" -> "java.util.UUID"
        "LocalDate" -> "java.time.LocalDate"
        "Instant" -> "java.time.Instant"
        "URI" -> "java.net.URI"
        else -> null
    }

    private val codecs = mapOf(
        "String" to "StringCodec",
        "Int" to "IntCodec",
        "Long" to "LongCodec",
        "Double" to "DoubleCodec",
        "Boolean" to "BooleanCodec",
        "UUID" to "UuidCodec",
        "LocalDate" to "LocalDateCodec",
        "Instant" to "InstantCodec",
        "URI" to "UriCodec",
    )

    /** Schema keywords a codec, a refinement or the declaration itself already carries. */
    private val carried = setOf(
        "type", "enum", "description", "title", "example", "examples", "deprecated", "readOnly",
        "writeOnly", "minLength", "maxLength", "pattern", "minimum", "maximum", "default",
    )

    private val knownFormats = setOf("uuid", "date", "date-time", "uri", "int32", "int64", "double", "float")

    private fun reason(status: Int): String = reasons[status] ?: "Status$status"

    private val banner = """
        // Generated from an OpenAPI document by pelican-import. Do not edit: the
        // task that wrote it will write it again, and the document is where the
        // change belongs.
    """.trimIndent()

    private val stubBanner = """
        // Started from an OpenAPI document by pelican-import, and yours from here.
        // Written once — the import task will not overwrite it — so the handlers
        // filled in below survive the next run.
    """.trimIndent()

    private val routeMethods = setOf("get", "post", "put", "patch", "delete")

    /** Builder names a scheme value must not shadow; see [schemeName]. */
    private val builders = setOf(
        "bearerAuth", "basicAuth", "httpAuth", "apiKeyHeader", "apiKeyQuery", "apiKeyCookie",
        "openIdConnect", "oauth2", "oauth2AuthorizationCode", "oauth2ClientCredentials",
        "oauth2Implicit", "oauth2Password",
    )

    private val reasons = mapOf(
        400 to "BadRequest",
        401 to "Unauthorized",
        403 to "Forbidden",
        404 to "NotFound",
        409 to "Conflict",
        410 to "Gone",
        422 to "Unprocessable",
        429 to "TooManyRequests",
        500 to "ServerError",
        503 to "Unavailable",
    )
}

/** A wire value as Kotlin sees it: the type, and the codec when one is needed. */
private class Plain(val type: String, val codec: String?)

/** A Kotlin type name and nothing else: not `List<Order>`, not `Order?`, not `String`. */
// ------------------------------------------------------------------ pieces
//
// Text, and nothing else: none of these reads the emitter's state, so they sit
// beside the constants below rather than inside the class. What they have in
// common is that they answer "how is this written down", which is a question
// about Kotlin syntax rather than about the document being read.

private fun description(text: String?) = text?.let { ", ${kotlinString(it)}" }.orEmpty()

private fun namedDescription(text: String?) =
    if (text == null) "" else ", description = ${kotlinString(text)}"

private fun describedBy(text: String?) = if (text == null) "" else kotlinString(text)

/**
 * One payload under several media types, as the `or` a hand-written description
 * would use. The description goes on the first alternative alone, which is
 * where `or` reads it from.
 */
private fun negotiatedDeclaration(body: IrBody.Negotiated, type: String): String =
    body.encodings.mapIndexed { index, encoding ->
        val described = if (index == 0) describedBy(body.description) else ""
        if (encoding == "application/json") "jsonBody<$type>($described)" else "formBody<$type>($described)"
    }.joinToString(" or ")

/**
 * A file part, streamed or held. The bound of a held one is written out rather
 * than left to a default, because a part that costs a caller-controlled
 * allocation on every request should say so where a reader of this file sees it.
 */
private fun fileDeclaration(part: IrPart.File): String {
    val contentType = part.contentType?.let { ", contentType = ${kotlinString(it)}" }.orEmpty()
    val call = part.bufferedBytes
        ?.let { "bufferedFile(${kotlinString(part.name)}, maxBytes = $it$contentType" }
        ?: "filePart(${kotlinString(part.name)}$contentType"
    return "$call${namedDescription(part.description)})"
}

private fun literal(value: io.github.matthewjones372.pelican.JsonValue, type: String): String = when (type) {
    "String" -> kotlinString((value as? JsonStr)?.value.orEmpty())
    "Long" -> "${value.render()}L"
    else -> value.render()
}

private fun StringBuilder.section(title: String, declarations: Collection<String>) {
    if (declarations.isEmpty()) return
    appendLine(rule(title))
    appendLine()
    declarations.forEach {
        appendLine(it)
        appendLine()
    }
}

private fun rule(title: String): String {
    val dashes = RULE_WIDTH - title.length
    return "// " + "-".repeat(maxOf(dashes, 1)) + " $title"
}

private fun String.capitalise() = replaceFirstChar { it.uppercaseChar() }

private val bareType = Regex("[A-Z][A-Za-z0-9_]*")

/** Well inside the JVM's 64KB ceiling on a string constant, with room for escapes. */
private const val CHUNK = 8_000

private const val OCTET_STREAM = "application/octet-stream"

private const val TUPLE_LIMIT = 6
private const val RULE_WIDTH = 68

// ------------------------------------------------------ security schemes

/**
 * A security scheme as the builder call that makes one.
 *
 * Text, like everything else down here, once the *name* the value is bound to
 * is somebody else's problem: the emitter mints that, because it has to stay
 * unique against every other declaration in the file.
 */
private fun schemeCall(scheme: IrScheme): String {
    val shared = buildList {
        add("name = ${kotlinString(scheme.name)}")
        scheme.description?.let { add("description = ${kotlinString(it)}") }
    }
    return when (scheme) {
        is IrScheme.ApiKey -> apiKey(scheme, shared)
        is IrScheme.Http -> http(scheme, shared)
        is IrScheme.OpenId -> call("openIdConnect", listOf(kotlinString(scheme.url)) + shared)
        is IrScheme.OAuth2 -> oauth2(scheme, shared)
    }
}

private fun apiKey(scheme: IrScheme.ApiKey, shared: List<String>): String {
    val builder = when (scheme.location) {
        "query" -> "apiKeyQuery"
        "cookie" -> "apiKeyCookie"
        else -> "apiKeyHeader"
    }
    return call(builder, listOf(kotlinString(scheme.paramName)) + shared)
}

private fun http(scheme: IrScheme.Http, shared: List<String>): String = when (scheme.scheme) {
    "basic" -> call("basicAuth", shared)

    // The default is "JWT", so a scheme that says nothing has to say so:
    // left off, the generated document would claim a bearer format the
    // original never mentioned.
    "bearer" -> call(
        "bearerAuth",
        listOf("bearerFormat = ${scheme.bearerFormat?.let(::kotlinString) ?: "null"}") + shared,
    )

    else -> call("httpAuth", listOf(kotlinString(scheme.scheme)) + shared)
}

private fun oauth2(scheme: IrScheme.OAuth2, shared: List<String>): String {
    val flows = scheme.flows.map { flow ->
        val arguments = buildList {
            flow.authorizationUrl?.let { add("authorizationUrl = ${kotlinString(it)}") }
            flow.tokenUrl?.let { add("tokenUrl = ${kotlinString(it)}") }
            flow.refreshUrl?.let { add("refreshUrl = ${kotlinString(it)}") }
            add("scopes = ${scopes(flow.scopes)}")
        }
        flowBuilder(flow.kind) to arguments
    }

    // One flow is the common case and reads as one call. Several have to
    // be built as flow values and handed over together, which is the shape
    // core offers for exactly this.
    return if (flows.size == 1) {
        val (builder, arguments) = flows.single()
        call(builder, arguments + shared)
    } else {
        val listed = flows.joinToString(",\n        ") { (builder, arguments) -> call(builder, arguments) }
        call("oauth2", listOf("listOf(\n        $listed,\n    )") + shared)
    }
}

private fun scopes(scopes: Map<String, String>): String {
    if (scopes.isEmpty()) return "emptyMap()"
    return "mapOf(${scopes.entries.joinToString { "${kotlinString(it.key)} to ${kotlinString(it.value)}" }})"
}

private fun flowBuilder(kind: String) = when (kind) {
    "implicit" -> "oauth2Implicit"
    "password" -> "oauth2Password"
    "clientCredentials" -> "oauth2ClientCredentials"
    else -> "oauth2AuthorizationCode"
}

private fun call(builder: String, arguments: List<String>) = "$builder(${arguments.joinToString()})"
