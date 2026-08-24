package dev.pelican.codegen

import dev.pelican.ApiSpec
import dev.pelican.ByteStreamOutput
import dev.pelican.EmptyOutput
import dev.pelican.Endpoint
import dev.pelican.ErrorOutput
import dev.pelican.FallibleOutput
import dev.pelican.FormBody
import dev.pelican.JsonArrayOutput
import dev.pelican.JsonBody
import dev.pelican.JsonObj
import dev.pelican.JsonOutput
import dev.pelican.ListStyle
import dev.pelican.MultipartBody
import dev.pelican.NdjsonOutput
import dev.pelican.Output
import dev.pelican.PathParam
import dev.pelican.PathSegment
import dev.pelican.PlainCodec
import dev.pelican.RawBody
import dev.pelican.ResponseHeader
import dev.pelican.SchemaRegistry
import dev.pelican.SecurityRequirement
import dev.pelican.SseOutput
import dev.pelican.TextOutput
import dev.pelican.operationName
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KType

/**
 * Interprets endpoint descriptions as Kotlin client source.
 *
 * A fourth reading of the same values, after the route, the document and the
 * typed test client. The test client is for callers who *hold* the
 * descriptions; this is for callers who cannot — a service in another
 * repository, another team, another release cycle. They get a file generated
 * from those descriptions instead: one method per endpoint, its parameters and
 * payloads typed from the same schemas the OpenAPI document publishes.
 *
 * ```
 * ordersSpec().writeKotlinClient(sourceRoot, packageName = "com.example.orders")
 * // -> <sourceRoot>/com/example/orders/OrdersClient.kt
 * ```
 *
 * [writeKotlinClient] is the one to reach for: it lays out the package
 * directories and writes the file. This function is the same thing as a string,
 * for a caller who wants to put it somewhere else.
 *
 * ```kotlin
 * val client = OrdersClient("https://orders.internal", JacksonCodecs)
 *
 * val user: User = client.getUser(1L)
 * client.listOrders(1L, limit = 3).forEach { println(it.item) }   // Streamed<Order>
 *
 * when (val result = client.placeOrder(1L, CreateOrder("anvil"), xApiKey = key)) {
 *     is Outcome.Ok  -> result.value
 *     is Outcome.Err -> when (val failure = result.failure) {     // exhaustive
 *         is PlaceOrderFailure.Unauthorized -> retryWith(freshKey())
 *         is PlaceOrderFailure.NotFound     -> null
 *     }
 * }
 * ```
 *
 * What the generated file needs on its classpath is `pelican-core`, which has
 * no third-party dependencies of its own, and a `Codecs` — `JacksonCodecs` or
 * `KotlinxCodecs`, chosen by the caller in exactly the place a server chooses
 * one. Nothing else: the transport is the JDK's own `HttpClient`.
 *
 * This module depends on `pelican-core` and nothing else either. Payload types
 * come from the [ApiSpec]'s own `SchemaSource`, so the client's types and the
 * document's schemas are the same schemas, not two derivations from the same
 * Kotlin classes.
 *
 * @param packageName the package the generated file declares.
 * @param clientName the class name; defaults to the title, e.g. `OrdersClient`.
 * @param baseUrl what the client points at when its caller does not say;
 *   defaults to the spec's first server. With neither, the caller must pass one.
 * @param includeHidden hidden endpoints are left out, as they are left out of
 *   the document. Generating an internal client is what this switch is for.
 * @param codec which JSON library the payload types are annotated for. It is
 *   the same choice, spelled the same way, that `ImportOptions.codec` makes for
 *   an imported description, and it matters for exactly one shape: a sealed
 *   hierarchy, which neither library can read off the Kotlin alone. A spec with
 *   no union generates the same file either way.
 */
fun ApiSpec.kotlinClient(
    packageName: String,
    clientName: String = defaultClientName(title),
    baseUrl: String? = servers.firstOrNull(),
    includeHidden: Boolean = false,
    codec: CodecAnnotations = CodecAnnotations.JACKSON,
): String = KotlinClientEmitter(this, packageName, clientName, baseUrl.orEmpty(), includeHidden, codec).emit()

/**
 * Writes the client into [sourceRoot], under the directories [packageName]
 * implies, and returns the file it wrote.
 *
 * Point it at a source root and it lands where the compiler already looks:
 *
 * ```
 * ordersSpec().writeKotlinClient(Path("src/main/kotlin"), packageName = "com.example.orders")
 * // -> src/main/kotlin/com/example/orders/OrdersClient.kt
 * ```
 *
 * Directories are created as needed and an existing file is replaced, so a
 * regeneration is the whole update — there is nothing to move afterwards.
 */
fun ApiSpec.writeKotlinClient(
    sourceRoot: Path,
    packageName: String,
    clientName: String = defaultClientName(title),
    baseUrl: String? = servers.firstOrNull(),
    includeHidden: Boolean = false,
    codec: CodecAnnotations = CodecAnnotations.JACKSON,
): Path {
    val directory = packageName.split('.')
        .filter { it.isNotEmpty() }
        .fold(sourceRoot) { path, part -> path.resolve(part) }

    Files.createDirectories(directory)
    val file = directory.resolve("$clientName.kt")
    Files.writeString(file, kotlinClient(packageName, clientName, baseUrl, includeHidden, codec))
    return file
}

/**
 * As above, for callers holding a [File] — Gradle's `layout`, chiefly.
 *
 * This is the one signature the Gradle plugin looks up by name rather than
 * calling, so [codec] is declared as a second arity rather than as a defaulted
 * parameter on the first. A default would compile to one seven-argument method
 * and leave the six-argument one existing nowhere, so a plugin release and a
 * library release would have to arrive together — which is the coupling the
 * reflective lookup exists to avoid. `importEndpoints` is the same bargain,
 * made for the same reason.
 */
fun ApiSpec.writeKotlinClient(
    sourceRoot: File,
    packageName: String,
    clientName: String = defaultClientName(title),
    baseUrl: String? = servers.firstOrNull(),
    includeHidden: Boolean = false,
    codec: CodecAnnotations,
): File = writeKotlinClient(sourceRoot.toPath(), packageName, clientName, baseUrl, includeHidden, codec).toFile()

/** The arity published before [codec] existed, and the default it stood for. */
fun ApiSpec.writeKotlinClient(
    sourceRoot: File,
    packageName: String,
    clientName: String = defaultClientName(title),
    baseUrl: String? = servers.firstOrNull(),
    includeHidden: Boolean = false,
): File = writeKotlinClient(sourceRoot, packageName, clientName, baseUrl, includeHidden, CodecAnnotations.JACKSON)

/** `Orders` -> `OrdersClient`; anything unusable falls back to `ApiClient`. */
fun defaultClientName(title: String): String {
    val name = typeName(title)
    return if (name == "Value") "ApiClient" else name + "Client"
}

// --------------------------------------------------------------------------

private class KotlinClientEmitter(
    private val spec: ApiSpec,
    private val packageName: String,
    private val clientName: String,
    private val baseUrl: String,
    includeHidden: Boolean,
    codec: CodecAnnotations,
) {
    private val endpoints = spec.endpoints.filter { includeHidden || !it.hidden }
    private val components = SchemaRegistry()
    private val types = KotlinTypes(codec)

    /** Resolved once per type: asking twice would register the component twice. */
    private val schemas = LinkedHashMap<KType, JsonObj>()

    /** Kotlin type -> the name of the `BodyCodec` val generated for it. */
    private val codecs = LinkedHashMap<String, String>()

    /**
     * The same, for the form-encoding codecs. Kept apart because a type can be
     * both a JSON body on one endpoint and a form on another, and the two
     * codecs read the same type differently.
     */
    private val formCodecs = LinkedHashMap<String, String>()

    /** Sealed failure declarations, keyed by name so the order stays stable. */
    private val failures = LinkedHashMap<String, String>()

    fun emit(): String {
        // Every payload type is resolved first, so the component registry is
        // complete before any of it is turned into a declaration.
        endpoints.forEach { ep ->
            when (val body = ep.bodyInput) {
                is JsonBody<*> -> schema(body.type)
                is FormBody<*> -> schema(body.type)
                else -> null
            }
            payloadType(ep.output)?.let { schema(it) }
            declaredFailures(ep).forEach { schema(it.type) }
        }
        types.declareAll(components.all())

        val methods = endpoints.joinToString("\n\n") { method(it) }
        // Written out before the imports, because an annotation the payload
        // types turned out to need is an import the file has to declare.
        val declarations = types.declarations()

        return buildString {
            appendLine(header())
            appendLine()
            appendLine("package $packageName")
            appendLine()
            val declared = IMPORTS.lines().filter { it.isNotBlank() } + types.imports().map { "import $it" }
            appendLine(declared.sorted().joinToString("\n"))
            appendLine()
            appendLine(resource("runtime.kt").trim())

            if (declarations.isNotEmpty()) {
                appendLine()
                appendLine(banner("payloads"))
                appendLine()
                appendLine(declarations.joinToString("\n\n"))
            }
            if (failures.isNotEmpty()) {
                appendLine()
                appendLine(banner("declared failures"))
                appendLine()
                appendLine(
                    "// One sealed type per endpoint that declares its failures, so a `when`\n" +
                        "// over the failure side of an Outcome is exhaustive.",
                )
                appendLine()
                appendLine(failures.values.joinToString("\n\n"))
            }

            appendLine()
            appendLine(banner("client"))
            appendLine()
            appendLine("private const val DEFAULT_BASE_URL = ${kotlinString(baseUrl)}")
            appendLine()
            spec.description?.let { appendLine(kdoc(it, "")) }
            appendLine("class $clientName(")
            appendLine(resource("client-constructor.kt").trimEnd())
            appendLine(") {")
            appendLine()
            appendLine(indent(resource("client-body.kt").trim(), "    "))
            if (codecs.isNotEmpty() || formCodecs.isNotEmpty()) {
                appendLine()
                appendLine(indent(codecDeclarations(), "    "))
            }
            appendLine()
            appendLine(methods)
            appendLine("}")
        }
    }

    private fun header(): String =
        """
        // Generated by Pelican from the endpoint descriptions of
        // ${spec.title} ${spec.version}. Do not edit: regenerate.
        //
        // Every method below is one endpoint value read a fourth way. The path, the
        // parameter names and the payload types are the ones the server routes and
        // the OpenAPI document publishes, so this file cannot describe a call the
        // service does not serve.
        //
        // Needs pelican-core on the classpath, and a Codecs to read bodies with.
        @file:Suppress("unused", "RedundantVisibilityModifier")
        """.trimIndent()

    /**
     * A `BodyCodec` per payload type, resolved when the client is constructed
     * rather than per call — the same point a server resolves its own.
     */
    private fun codecDeclarations(): String = (
        codecs.entries.map { (type, name) ->
            "private val $name: BodyCodec<$type> = codecs.codec(typeOf<$type>())"
        } + formCodecs.entries.map { (type, name) ->
            "private val $name: BodyCodec<$type> = codecs.formCodec(typeOf<$type>())"
        }
        ).joinToString("\n")

    // ------------------------------------------------------------ one endpoint

    private fun method(ep: Endpoint<*, *>): String {
        val call = call(ep)
        val name = memberName(ep.operationName)
        val out = ep.output
        val declared = out as? FallibleOutput<*, *>
        val success = declared?.success ?: out

        val returns = when {
            declared != null -> "Outcome<${failureType(ep, declared)}, ${successType(success)}>"
            else -> successType(success)
        }
        val signature = "    fun $name(${call.parameters})" + if (returns == "Unit") " {" else ": $returns {"

        return buildString {
            appendLine(doc(ep))
            appendLine(signature)
            appendLine(indent(body(ep, call, success, declared), "        "))
            append("    }")
        }
    }

    private fun body(
        ep: Endpoint<*, *>,
        call: Call,
        out: Output<*>,
        declared: FallibleOutput<*, *>?,
    ): String = buildString {
        val method = kotlinString(ep.method.name)
        val template = kotlinString(ep.pathSpec.template)
        val streamed = isStream(out) || out is ByteStreamOutput

        appendLine("val response = ${if (streamed) "stream" else "text"}(${call.request})")

        if (declared != null) {
            appendLine("when (response.statusCode()) {")
            declared.failures.forEach { failure ->
                val payload = decodeExpression(failure.type, if (streamed) "drain(response)" else "response.body()")
                val headers = failure.headers.joinToString("") { ", ${headerRead(it)}" }
                appendLine(
                    "    ${failure.status} -> return Outcome.Err(" +
                        "${failureType(ep, declared)}.${failureMember(failure.status)}($payload$headers))",
                )
            }
            appendLine("}")
        }

        val fail = if (streamed) "failedStream" else "failed"
        appendLine("if (!response.succeeded()) $fail($method, $template, response)")

        val produced = when {
            isStream(out) -> {
                appendLine("val body = response.body()")
                "Streamed(body, ${frames(out)}.map { ${decodeExpression(elementType(out), "it")} })"
            }

            out is ByteStreamOutput -> "response.body()"

            out is TextOutput -> "response.body()"

            out is EmptyOutput -> null

            out is JsonOutput<*> -> decodeExpression(out.type, "response.body()")

            else -> null
        }

        when {
            produced == null && declared != null -> append("return Outcome.Ok(Unit)")

            produced == null -> Unit

            // 204: there is nothing to hand back
            declared != null -> append("return Outcome.Ok($produced)")

            else -> append("return $produced")
        }
    }.trimEnd()

    // ------------------------------------------------------------ signatures

    /** What the method declares, and the `request(...)` call that fills it in. */
    private class Call(val parameters: String, val request: String)

    // Assembles one client method from one description: the parameter list,
    // the naming that keeps it collision-free, the body, and the call. The
    // branches are the body kinds, and each one both names a parameter and
    // says how the request is built from it — pulling them out would mean
    // passing this function's naming state along with them, which is a worse
    // shape than the length.
    @Suppress("CyclomaticComplexMethod")
    private fun call(ep: Endpoint<*, *>): Call {
        val taken = mutableSetOf<String>()
        val pathNames = LinkedHashMap<PathParam<*>, String>()
        ep.pathSpec.captures.forEach { pathNames[it] = unique(memberName(it.name), taken) }

        val required = mutableListOf<String>()
        val optional = mutableListOf<String>()

        pathNames.forEach { (param, name) ->
            required += "$name: ${plainType(param.codec, types, param.name)}"
        }

        // Query parameters, headers and cookies are named parameters with
        // defaults, which is what Kotlin has instead of an options object.
        // Absent means absent: null is not sent, so the server applies its own
        // default.
        val queryPairs = mutableListOf<String>()
        val headerPairs = mutableListOf<String>()
        val cookiePairs = mutableListOf<String>()

        fun parameter(
            wire: String,
            codec: PlainCodec<*>,
            isRequired: Boolean,
            listStyle: ListStyle?,
            pairs: MutableList<String>,
        ) {
            val name = unique(memberName(wire), taken)
            val element = plainType(codec, types, wire)
            val type = if (listStyle == null) element else "List<$element>"
            if (isRequired) required += "$name: $type" else optional += "$name: $type? = null"
            pairs += "${kotlinString(wire)} to ${occurrences(name, listStyle)}"
        }

        val bodyName = unique("body", taken)
        val bodyArgument = when (val input = ep.bodyInput) {
            is JsonBody<*> -> {
                required += "$bodyName: ${typeFor(input.type)}"
                "body = HttpRequest.BodyPublishers.ofString(" +
                    "${codecName(typeFor(input.type))}.encodeToString($bodyName)), " +
                    "contentType = \"application/json\""
            }

            // Encoded by core, against the same published schema the server
            // decodes it against — so the caller writes a `SignIn` and the
            // pairs on the wire are the ones that type decodes back from.
            is FormBody<*> -> {
                required += "$bodyName: ${typeFor(input.type)}"
                "body = HttpRequest.BodyPublishers.ofString(" +
                    "${formCodecName(typeFor(input.type))}.encodeToString($bodyName)), " +
                    "contentType = \"application/x-www-form-urlencoded\""
            }

            // A text part is a typed parameter like any other; a file part is
            // an UploadedFile, which is the same value a handler is given on
            // the other side of the wire.
            is MultipartBody -> {
                val fields = mutableListOf<String>()
                val files = mutableListOf<String>()
                input.textParts.forEach { parameter(it.name, it.codec, it.required, null, fields) }
                input.fileParts.forEach { part ->
                    val name = unique(memberName(part.name), taken)
                    if (part.required) required += "$name: UploadedFile"
                    else optional += "$name: UploadedFile? = null"
                    files += "${kotlinString(part.name)} to $name"
                }
                "multipart = multipart(" +
                    "fields = listOf(${fields.joinToString(", ")}), " +
                    "files = listOf(${files.joinToString(", ")}))"
            }

            is RawBody -> {
                required += "$bodyName: InputStream"
                "body = HttpRequest.BodyPublishers.ofInputStream { $bodyName }, " +
                    "contentType = \"application/octet-stream\""
            }

            null -> null
        }

        ep.queries.forEach { parameter(it.name, it.codec, it.required, it.listStyle, queryPairs) }
        ep.headerParams.forEach { parameter(it.name, it.codec, it.required, it.listStyle, headerPairs) }
        ep.cookieParams.forEach { parameter(it.name, it.codec, it.required, it.listStyle, cookiePairs) }

        val arguments = buildList {
            add(kotlinString(ep.method.name))
            add(pathExpression(ep, pathNames))
            if (queryPairs.isNotEmpty()) add("query = listOf(${queryPairs.joinToString(", ")})")
            if (headerPairs.isNotEmpty()) add("headerParams = listOf(${headerPairs.joinToString(", ")})")
            if (cookiePairs.isNotEmpty()) add("cookies = listOf(${cookiePairs.joinToString(", ")})")
            bodyArgument?.let { add(it) }
        }

        return Call(
            parameters = (required + optional).joinToString(", "),
            request = "request(${arguments.joinToString(", ")})",
        )
    }

    /**
     * What the caller's argument contributes to `request(...)`.
     *
     * A repeated list is handed over whole and spread by the runtime, since
     * how many occurrences it becomes is a property of the value. A delimited
     * one is joined here, because the separator is a property of the
     * *declaration* and the runtime has no way to see it from a `List`.
     */
    private fun occurrences(name: String, listStyle: ListStyle?): String =
        when (val separator = listStyle?.separator) {
            null -> name
            else -> "joined($name, ${kotlinString(separator.toString())})"
        }

    private fun pathExpression(ep: Endpoint<*, *>, names: Map<PathParam<*>, String>): String {
        val template = ep.pathSpec.segments.joinToString("/", "/") { segment ->
            when (segment) {
                is PathSegment.Literal -> escapeTemplate(segment.value)
                is PathSegment.Capture -> "\${segment(${names.getValue(segment.param)})}"
            }
        }
        return "\"$template\""
    }

    // ------------------------------------------------------------ outputs

    private fun isStream(out: Output<*>): Boolean =
        out is NdjsonOutput<*> || out is SseOutput<*> || out is JsonArrayOutput<*>

    private fun elementType(out: Output<*>): KType = when (out) {
        is NdjsonOutput<*> -> out.type
        is SseOutput<*> -> out.type
        is JsonArrayOutput<*> -> out.type
        else -> error("$out does not stream")
    }

    private fun frames(out: Output<*>): String = when (out) {
        is NdjsonOutput<*> -> "ndjsonFrames(body.bufferedReader())"
        is SseOutput<*> -> "sseFrames(body.bufferedReader())"
        else -> "jsonArrayFrames(body.reader())"
    }

    private fun successType(out: Output<*>): String = when (out) {
        is JsonOutput<*> -> typeFor(out.type)

        is TextOutput -> "String"

        is EmptyOutput -> "Unit"

        // Opaque bytes stay opaque: the caller reads the stream, and closes it.
        is ByteStreamOutput -> "InputStream"

        else -> if (isStream(out)) "Streamed<${typeFor(elementType(out))}>" else "Unit"
    }

    private fun payloadType(out: Output<*>): KType? = when (out) {
        is FallibleOutput<*, *> -> payloadType(out.success)
        else -> out.payloadType
    }

    private fun declaredFailures(ep: Endpoint<*, *>): List<ErrorOutput<*>> =
        (ep.output as? FallibleOutput<*, *>)?.failures.orEmpty()

    /**
     * The sealed type an endpoint's declared failures become. One member per
     * declared status, named after it, carrying the payload that status was
     * declared with — and a property per header that status was declared to
     * send, so a `Retry-After` reaches the caller as a number rather than as
     * something to go and dig out of the response.
     */
    private fun failureType(ep: Endpoint<*, *>, out: FallibleOutput<*, *>): String {
        val name = typeName(ep.operationName) + "Failure"
        failures[name] = buildString {
            appendLine(kdoc("What `${memberName(ep.operationName)}` declares it can fail with.", ""))
            appendLine("sealed interface $name {")
            appendLine("    val status: Int")
            out.failures.forEach { failure ->
                appendLine()
                appendLine(kdoc(failure.description, "    "))
                val body = typeFor(failure.type)
                val properties = listOf("val body: $body") + failure.headers.map(::headerProperty)
                appendLine("    data class ${failureMember(failure.status)}(${properties.joinToString()}) : $name {")
                appendLine("        override val status: Int get() = ${failure.status}")
                appendLine("    }")
            }
            append("}")
        }
        return name
    }

    /**
     * Nullable whatever the declaration says, because this is the reading end:
     * a server that promised a header and left it off is something the caller
     * has to be able to see, and a client that threw would have replaced the
     * failure it was handed with one of its own. The same reason every other
     * unmodelled thing degrades here rather than failing.
     */
    private fun headerProperty(header: ResponseHeader<*>): String =
        "val ${memberName(header.name)}: ${headerType(header.codec)}?"

    /** The header off the response, parsed to the type the declaration gives it. */
    private fun headerRead(header: ResponseHeader<*>): String =
        "response.header(${kotlinString(header.name)})${headerParse(headerType(header.codec))}"

    // ------------------------------------------------------------ odds and ends

    private fun schema(type: KType): JsonObj =
        schemas.getOrPut(type) { spec.schemas.schema(type, components) }

    private fun typeFor(type: KType): String = types.type(schema(type), "Payload")

    private fun decodeExpression(type: KType, from: String): String =
        "${codecName(typeFor(type))}.decodeFromString($from)"

    /** The `BodyCodec` val for a payload type, declared on first use. */
    private fun codecName(type: String): String =
        codecs.getOrPut(type) { memberName(type) + "Codec" }

    /** The same, for a type carried as `application/x-www-form-urlencoded`. */
    private fun formCodecName(type: String): String =
        formCodecs.getOrPut(type) { memberName(type) + "FormCodec" }

    private fun doc(ep: Endpoint<*, *>): String {
        val lines = buildList {
            ep.summary?.let { add(it) }
            ep.description?.takeIf { it != ep.summary }?.let { add(""); add(it) }
            add("")
            add("`${ep.method.name} ${ep.pathSpec.template}`")
            requirements(ep)?.let { add(it) }
            if (ep.deprecated) { add(""); add("@deprecated") }
        }
        return kdoc(lines.joinToString("\n").trim(), "    ")
    }

    /**
     * What a caller has to present. Documentation, here as everywhere else in
     * Pelican — the generated client sends what you give it and checks nothing.
     */
    private fun requirements(ep: Endpoint<*, *>): String? {
        val required: List<SecurityRequirement> = ep.security ?: spec.security
        if (required.isEmpty()) return null
        return "Requires: " + required.joinToString(", or ") { requirement ->
            requirement.scheme.name +
                if (requirement.scopes.isEmpty()) "" else " (${requirement.scopes.joinToString(" ")})"
        }
    }
}

// ------------------------------------------------------------------ helpers

/** The member name for a declared failure's status: 404 -> `NotFound`. */
private fun failureMember(status: Int): String = when (status) {
    400 -> "BadRequest"
    401 -> "Unauthorized"
    402 -> "PaymentRequired"
    403 -> "Forbidden"
    404 -> "NotFound"
    405 -> "MethodNotAllowed"
    406 -> "NotAcceptable"
    408 -> "RequestTimeout"
    409 -> "Conflict"
    410 -> "Gone"
    412 -> "PreconditionFailed"
    413 -> "PayloadTooLarge"
    415 -> "UnsupportedMediaType"
    422 -> "UnprocessableEntity"
    423 -> "Locked"
    428 -> "PreconditionRequired"
    429 -> "TooManyRequests"
    451 -> "UnavailableForLegalReasons"
    500 -> "ServerError"
    501 -> "NotImplemented"
    502 -> "BadGateway"
    503 -> "ServiceUnavailable"
    504 -> "GatewayTimeout"
    else -> "Status$status"
}

/**
 * A response header's Kotlin type.
 *
 * The four scalar kinds and String, rather than [plainType]'s reading, which
 * also mints an enum for a constrained parameter. An enum is right on the way
 * *out*, where the caller picks a value the client then writes as a string; on
 * the way back in it would mean matching a string the server chose against the
 * constants the document listed, and a header that did not match would leave
 * the client holding no value for a failure that did arrive.
 */
private fun headerType(codec: PlainCodec<*>): String = when (codec.openApiType) {
    "integer" -> if (codec.openApiFormat == "int64") "Long" else "Int"
    "number" -> "Double"
    "boolean" -> "Boolean"
    else -> "String"
}

/**
 * Total parses only: a `Retry-After` carrying something that is not a number
 * comes back null rather than throwing, for the same reason the property is
 * nullable at all.
 */
private fun headerParse(type: String): String = when (type) {
    "Long" -> "?.toLongOrNull()"
    "Int" -> "?.toIntOrNull()"
    "Double" -> "?.toDoubleOrNull()"
    "Boolean" -> "?.toBooleanStrictOrNull()"
    else -> ""
}

/** A literal path segment inside a Kotlin string template. */
private fun escapeTemplate(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

private fun banner(title: String): String =
    "// " + "-".repeat((BANNER_WIDTH - title.length).coerceAtLeast(MIN_BANNER_RULE)) + " $title"

private fun resource(name: String): String =
    KotlinClientEmitter::class.java.getResourceAsStream(name)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("Missing generator resource: $name")

private val IMPORTS = """
    import dev.pelican.BodyCodec
    import dev.pelican.Codecs
    import dev.pelican.UploadedFile
    import dev.pelican.formCodec
    import java.io.BufferedReader
    import java.io.ByteArrayInputStream
    import java.io.InputStream
    import java.io.Reader
    import java.io.SequenceInputStream
    import java.net.URI
    import java.net.URLEncoder
    import java.net.http.HttpClient
    import java.net.http.HttpRequest
    import java.net.http.HttpResponse
    import java.nio.charset.StandardCharsets
    import java.time.Duration
    import java.util.Collections
    import java.util.UUID
    import kotlin.reflect.typeOf
""".trimIndent()

/** The column the generated banner comments rule out to. */
private const val BANNER_WIDTH = 72

/** A banner for a very long title still gets a rule, even a short one. */
private const val MIN_BANNER_RULE = 3
