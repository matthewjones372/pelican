package io.github.matthewjones372.pelican.codegen

import io.github.matthewjones372.pelican.ApiSpec
import io.github.matthewjones372.pelican.BodyInput
import io.github.matthewjones372.pelican.ByteStreamOutput
import io.github.matthewjones372.pelican.EmptyOutput
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.ErrorOutput
import io.github.matthewjones372.pelican.FallibleOutput
import io.github.matthewjones372.pelican.FilePart
import io.github.matthewjones372.pelican.FormBody
import io.github.matthewjones372.pelican.JsonArrayOutput
import io.github.matthewjones372.pelican.JsonBody
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonOutput
import io.github.matthewjones372.pelican.ListStyle
import io.github.matthewjones372.pelican.MultipartBody
import io.github.matthewjones372.pelican.NdjsonOutput
import io.github.matthewjones372.pelican.NegotiatedBody
import io.github.matthewjones372.pelican.Output
import io.github.matthewjones372.pelican.PathParam
import io.github.matthewjones372.pelican.PathSegment
import io.github.matthewjones372.pelican.PlainCodec
import io.github.matthewjones372.pelican.RawBody
import io.github.matthewjones372.pelican.ResponseHeader
import io.github.matthewjones372.pelican.SchemaRegistry
import io.github.matthewjones372.pelican.SecurityRequirement
import io.github.matthewjones372.pelican.SseOutput
import io.github.matthewjones372.pelican.TextOutput
import io.github.matthewjones372.pelican.TextPart
import io.github.matthewjones372.pelican.mediaType
import io.github.matthewjones372.pelican.operationName
import io.github.matthewjones372.pelican.payloadType
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KType

/**
 * Interprets endpoint descriptions as Kotlin client source.
 *
 * A fourth reading of the same values, after the route, the document and the
 * typed test client. That one is for callers who hold the descriptions; this is
 * for callers who cannot — another repository, another team — and gives them a
 * file instead: one method per endpoint, typed from the same schemas the
 * document publishes.
 *
 * [writeKotlinClient] lays out the package directories and writes the file;
 * this returns the same thing as a string.
 *
 * The generated file needs `pelican-core`, a `Codecs`, and a `ClientTransport`
 * on its classpath, and nothing else. The transport is an interface core owns
 * rather than an HTTP library, so a caller already running one supplies it;
 * `pelican-client-java` is the adapter over the JDK's own `HttpClient` and the
 * one a client finds unless the build names another. Payload types come from
 * the [ApiSpec]'s own `SchemaSource`, so the client's types and the document's
 * schemas are the same schemas.
 *
 * @param packageName the package the generated file declares.
 * @param clientName the class name; defaults to the title, e.g. `OrdersClient`.
 * @param baseUrl what the client points at when its caller does not say;
 *   defaults to the spec's first server. An endpoint declaring its own
 *   `servers` is called there whatever this says.
 * @param includeHidden hidden endpoints are left out, as they are left out of
 *   the document. Generating an internal client is what this switch is for.
 * @param codec which JSON library the payload types are annotated for. Matters
 *   for one shape only — a sealed hierarchy, which neither library can read off
 *   the Kotlin alone.
 * @param callStyle whether the methods block or suspend. See [CallStyle]: one
 *   or the other, not both, and blocking unless the caller says otherwise.
 */
fun ApiSpec.kotlinClient(
    packageName: String,
    clientName: String = defaultClientName(title),
    baseUrl: String? = servers.firstOrNull(),
    includeHidden: Boolean = false,
    codec: CodecAnnotations = CodecAnnotations.JACKSON,
    callStyle: CallStyle = CallStyle.BLOCKING,
): String =
    KotlinClientEmitter(this, packageName, clientName, baseUrl.orEmpty(), includeHidden, codec, callStyle).emit()

/**
 * Whether the generated methods block or suspend.
 *
 * One call surface per generated file rather than both on one class. Both would
 * mean two methods per operation — `listOrders` and something spelled slightly
 * differently — on a class whose whole appeal is that it has one method per
 * endpoint under the endpoint's own name. It would also put kotlinx.coroutines
 * on the classpath of every caller that generated a client, including the ones
 * that will never call a suspending method, and leave a blocking method within
 * reach of a coroutine that calls it by accident and parks a dispatcher thread
 * for the length of an HTTP call — the exact cost the suspending surface exists
 * to avoid.
 *
 * The choice does not divide the audience, because the file is generated in the
 * calling project rather than published from the described one: each caller
 * generates the surface it wants from the same descriptions. The method names,
 * the parameters, the payload types and the sealed failures are identical
 * either way, so moving between them is a line in a build file and nothing in
 * the code that calls it.
 */
enum class CallStyle {
    /** `fun listOrders(...)`, which joins the transport's stage. */
    BLOCKING,

    /**
     * `suspend fun listOrders(...)`, which awaits it. The generated file then
     * needs `org.jetbrains.kotlinx:kotlinx-coroutines-core` alongside
     * `pelican-core`, and a cancelled coroutine cancels the exchange.
     */
    SUSPENDING,
}

/**
 * Writes the client into [sourceRoot] under the directories [packageName]
 * implies, and returns the file. Directories are created as needed and an
 * existing file is replaced, so regenerating is the whole update.
 */
fun ApiSpec.writeKotlinClient(
    sourceRoot: Path,
    packageName: String,
    clientName: String = defaultClientName(title),
    baseUrl: String? = servers.firstOrNull(),
    includeHidden: Boolean = false,
    codec: CodecAnnotations = CodecAnnotations.JACKSON,
    callStyle: CallStyle = CallStyle.BLOCKING,
): Path {
    val directory = packageName.split('.')
        .filter { it.isNotEmpty() }
        .fold(sourceRoot) { path, part -> path.resolve(part) }

    Files.createDirectories(directory)
    val file = directory.resolve("$clientName.kt")
    Files.writeString(file, kotlinClient(packageName, clientName, baseUrl, includeHidden, codec, callStyle))
    return file
}

/**
 * As above, for callers holding a [File] — Gradle's `layout`, chiefly.
 *
 * The Gradle plugin looks these up by name, so each setting the generator has
 * grown is a further arity rather than a defaulted parameter: a default would
 * leave the shorter method existing nowhere, forcing plugin and library
 * releases to arrive together. The plugin asks for the longest signature it
 * knows about and falls back through the ones below.
 */
fun ApiSpec.writeKotlinClient(
    sourceRoot: File,
    packageName: String,
    clientName: String = defaultClientName(title),
    baseUrl: String? = servers.firstOrNull(),
    includeHidden: Boolean = false,
    codec: CodecAnnotations,
    callStyle: CallStyle,
): File =
    writeKotlinClient(sourceRoot.toPath(), packageName, clientName, baseUrl, includeHidden, codec, callStyle).toFile()

/** The arity published before [callStyle] existed, and the default it stood for. */
fun ApiSpec.writeKotlinClient(
    sourceRoot: File,
    packageName: String,
    clientName: String = defaultClientName(title),
    baseUrl: String? = servers.firstOrNull(),
    includeHidden: Boolean = false,
    codec: CodecAnnotations,
): File = writeKotlinClient(sourceRoot, packageName, clientName, baseUrl, includeHidden, codec, CallStyle.BLOCKING)

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
    private val callStyle: CallStyle,
) {
    private val suspending = callStyle == CallStyle.SUSPENDING

    private val endpoints = spec.endpoints.filter { includeHidden || !it.hidden }

    /**
     * The calls this service sends, generated as senders on the same class.
     * Built by the same [method] and [call] as an endpoint's, since turning a
     * description into an outbound call is what this generator already does.
     */
    private val webhooks = spec.webhooks.filter { includeHidden || !it.operation.hidden }
    private val components = SchemaRegistry()
    private val types = KotlinTypes(codec)

    /** Resolved once per type: asking twice would register the component twice. */
    private val schemas = LinkedHashMap<KType, JsonObj>()

    /** Kotlin type -> the name of the `BodyCodec` val generated for it. */
    private val codecs = LinkedHashMap<String, String>()

    /**
     * The same, for form-encoding codecs. Kept apart because a type can be a
     * JSON body on one endpoint and a form on another.
     */
    private val formCodecs = LinkedHashMap<String, String>()

    /** Sealed failure declarations, keyed by name so the order stays stable. */
    private val failures = LinkedHashMap<String, String>()

    /**
     * The same, for an endpoint declaring several successes. Apart from
     * [failures] because a caller reaches them differently: one of these is the
     * value the call produced.
     */
    private val results = LinkedHashMap<String, String>()

    fun emit(): String {
        // Resolved first, so the registry is complete before any of it becomes
        // a declaration.
        (endpoints + webhooks.map { it.operation }).forEach { ep ->
            when (val body = ep.bodyInput) {
                is JsonBody<*>, is FormBody<*>, is NegotiatedBody<*> -> schema(checkNotNull(body.payloadType))
                else -> null
            }
            declaredSuccesses(ep).forEach { out -> out.payloadType?.let { schema(it) } }
            declaredFailures(ep).forEach { schema(it.type) }
        }
        types.declareAll(components.all())

        val methods = endpoints.joinToString("\n\n") { method(it) }
        val senders = webhooks.joinToString("\n\n") { method(it.operation) }
        // Before the imports: an annotation the payload types needed is an
        // import the file has to declare.
        val declarations = types.declarations()

        return buildString {
            appendLine(header())
            appendLine()
            appendLine("package $packageName")
            appendLine()
            val declared = imports().filter { it.isNotBlank() } + types.imports().map { "import $it" }
            appendLine(declared.sorted().joinToString("\n"))
            appendLine()
            appendLine(resource("runtime.kt").trim())

            if (declarations.isNotEmpty()) {
                appendLine()
                appendLine(banner("payloads"))
                appendLine()
                appendLine(declarations.joinToString("\n\n"))
            }
            append(
                section(
                    "declared responses",
                    results,
                    "// One sealed type per endpoint answering with more than one 2xx, so a\n" +
                        "// `when` over what the call produced is exhaustive and a 200 and a 201\n" +
                        "// carrying the same payload stay two different things.",
                ),
            )
            append(
                section(
                    "declared failures",
                    failures,
                    "// One sealed type per endpoint that declares its failures, so a `when`\n" +
                        "// over the failure side of an Outcome is exhaustive.",
                ),
            )

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
            appendLine(indent(resource("client-base.kt").trim(), "    "))

            // After the base URL, because an init block reading a property
            // must follow it. An empty base builds a relative URI, which the
            // JDK refuses — better said at construction than at the first call.
            // Left out where every operation named its own server.
            if (endpoints.any { it.servers.isEmpty() }) {
                appendLine()
                appendLine(indent(resource("client-base-check.kt").trim(), "    "))
            }

            appendLine()
            appendLine(indent(resource("client-body.kt").trim(), "    "))
            appendLine()
            appendLine(indent(resource(exchangeResource()).trim(), "    "))
            if (codecs.isNotEmpty() || formCodecs.isNotEmpty()) {
                appendLine()
                appendLine(indent(codecDeclarations(), "    "))
            }
            appendLine()
            appendLine(methods)
            append(sendersSection(senders))
            appendLine("}")
        }
    }

    /** The senders under their own banner, or nothing where the document declares no webhook. */
    private fun sendersSection(senders: String): String = if (senders.isEmpty()) "" else buildString {
        appendLine()
        appendLine(indent(banner("webhooks sent"), "    "))
        appendLine()
        appendLine(
            "    // One per webhook the document declares: a call this service makes to a\n" +
                "    // subscriber. The destination is the first argument because the document\n" +
                "    // does not know it — a subscriber does.",
        )
        appendLine()
        appendLine(senders)
    }

    /** One banner and the declarations under it, or nothing where there are none. */
    private fun section(title: String, declarations: Map<String, String>, note: String): String =
        if (declarations.isEmpty()) "" else buildString {
            appendLine()
            appendLine(banner(title))
            appendLine()
            appendLine(note)
            appendLine()
            appendLine(declarations.values.joinToString("\n\n"))
        }

    /**
     * Assembled rather than written as one literal, because `trimIndent` reads
     * the string it is given after the interpolation has happened: a note
     * inserted into it as several lines would take the whole header's
     * indentation with it.
     */
    private fun header(): String {
        val preamble =
            """
            // Generated by Pelican from the endpoint descriptions of
            // ${spec.title} ${spec.version}. Do not edit: regenerate.
            //
            // Every method below is one endpoint value read a fourth way: the path,
            // the parameter names and the payload types are the ones the server routes
            // and the document publishes.
            //
            // Needs pelican-core on the classpath, a Codecs to read bodies with, and
            // a ClientTransport to send with — pelican-client-java, unless you have
            // an HTTP client of your own to hand over.
            """.trimIndent()

        val suppress = """@file:Suppress("unused", "RedundantVisibilityModifier")"""
        return listOfNotNull(preamble, suspendingNote(), suppress).joinToString("\n")
    }

    /**
     * What the suspending shape has to say for itself in the header, and
     * nothing at all for the blocking one, whose classpath is what it was.
     */
    private fun suspendingNote(): String? =
        if (!suspending) null
        else """
            //
            // The methods here suspend, so this file also needs
            // org.jetbrains.kotlinx:kotlinx-coroutines-core. A call is cancelled by
            // cancelling the coroutine that made it, which cancels the exchange
            // underneath rather than leaving it running with nobody to read it.
        """.trimIndent()

    /** The exchange helpers, which are the whole of what the two call shapes disagree about. */
    private fun exchangeResource(): String = when (callStyle) {
        CallStyle.BLOCKING -> "client-exchange.kt"
        CallStyle.SUSPENDING -> "client-exchange-suspend.kt"
    }

    /**
     * What the generated file imports: the fixed list, less the one import only
     * a blocking client has a use for, plus the coroutine bridge a suspending
     * one is written in.
     */
    private fun imports(): List<String> = if (!suspending) IMPORTS.lines() else {
        IMPORTS.lines().filterNot { it.contains("java.util.concurrent.CompletionException") } +
            listOf(
                "import kotlinx.coroutines.Dispatchers",
                "import kotlinx.coroutines.future.await",
                "import kotlinx.coroutines.withContext",
            )
    }

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
        val successes = declaredSuccesses(ep)
        val failures = declaredFailures(ep)

        // Several successes become a sealed type of their own, so a caller has
        // to say which one it is looking at. One success is what it always
        // was — the value itself, with no wrapper to unpick.
        val produces =
            if (successes.size > 1) resultType(ep, successes) else successType(successes.single())

        // The `Outcome` is the *failure* side's doing. An endpoint that answers
        // two ways and declares no failure hands back the sealed type directly,
        // rather than an `Outcome` whose `Err` branch nothing could reach.
        val returns = if (failures.isEmpty()) produces else "Outcome<${failureType(ep, failures)}, $produces>"
        val keyword = if (suspending) "suspend fun" else "fun"
        val signature = "    $keyword $name(${call.parameters})" + if (returns == "Unit") " {" else ": $returns {"

        return buildString {
            appendLine(doc(ep))
            appendLine(signature)
            appendLine(indent(body(ep, call, successes), "        "))
            append("    }")
        }
    }

    private fun body(
        ep: Endpoint<*, *>,
        call: Call,
        successes: List<Output<*>>,
    ): String = buildString {
        val method = "Method.${ep.method.name}"
        // What an unexpected status is reported against. A webhook has no path,
        // so it is the URL the caller sent it to — which is the only thing about
        // that call worth naming in the failure.
        val template = call.target ?: kotlinString(ep.pathSpec.template)
        val at = CallSite(method, template)
        val out = successes.first()
        val streamed = streams(ep)
        val failures = declaredFailures(ep)

        appendLine("val response = ${if (streamed) "stream" else "text"}(${call.request})")

        if (failures.isNotEmpty()) appendLine(declaredFailureBranches(ep, failures, streamed, at))

        val fail = if (streamed) "failedStream" else "failed"
        appendLine("if (!response.succeeded()) $fail($method, $template, response)")

        if (successes.size > 1) {
            append(chosenByStatus(ep, successes, failures, "$fail($method, $template, response)", at))
            return@buildString
        }

        val produced = when {
            isStream(out) -> {
                appendLine("val body = response.body")
                "Streamed(body, ${frames(out)}.map { ${decodeExpression(elementType(out), "it", at)} })"
            }

            out is ByteStreamOutput -> "response.body"

            out is TextOutput -> "response.body"

            out is EmptyOutput -> null

            out is JsonOutput<*> -> decodeExpression(out.type, "response.body", at)

            else -> null
        }

        when {
            produced == null && failures.isNotEmpty() -> append("return Outcome.Ok(Unit)")

            produced == null -> Unit

            // 204: there is nothing to hand back
            failures.isNotEmpty() -> append("return Outcome.Ok($produced)")

            else -> append("return $produced")
        }
    }.trimEnd()

    /**
     * The declared failures, matched on status first: a declared failure is not
     * a failed call but one of the answers the endpoint said it gives, so it
     * reaches the caller on the `Err` side rather than as a throw.
     */
    private fun declaredFailureBranches(
        ep: Endpoint<*, *>,
        failures: List<ErrorOutput<*>>,
        streamed: Boolean,
        at: CallSite,
    ): String = buildString {
        appendLine("when (response.status) {")
        failures.forEach { failure ->
            val payload =
                decodeExpression(failure.type, if (streamed) "drain(response)" else "response.body", at)
            val headers = failure.headers.joinToString("") { ", ${headerRead(it)}" }
            appendLine(
                "    ${failure.status} -> return Outcome.Err(" +
                    "${failureType(ep, failures)}.${failureMember(failure.status)}($payload$headers))",
            )
        }
        append("}")
    }

    /**
     * Which declared success came back, read the only way a caller can: by
     * status. Two 2xx sharing one are refused at declaration, so at most one
     * branch matches; a 2xx outside the set fails the call.
     */
    private fun chosenByStatus(
        ep: Endpoint<*, *>,
        successes: List<Output<*>>,
        failures: List<ErrorOutput<*>>,
        otherwise: String,
        at: CallSite,
    ): String = buildString {
        appendLine("return when (response.status) {")
        successes.forEach { success ->
            appendLine("    ${success.status} -> ${wrap(resultExpression(ep, successes, success, at), failures)}")
        }
        appendLine("    else -> $otherwise")
        append("}")
    }

    /** The `Outcome.Ok` wrapper, where there is a failure side for it to be the other half of. */
    private fun wrap(expression: String, failures: List<ErrorOutput<*>>): String =
        if (failures.isEmpty()) expression else "Outcome.Ok($expression)"

    /** One member of an endpoint's result type, built from the response body and its headers. */
    private fun resultExpression(
        ep: Endpoint<*, *>,
        successes: List<Output<*>>,
        success: Output<*>,
        at: CallSite,
    ): String {
        val name = "${resultType(ep, successes)}.${successMember(success.status)}"
        val arguments = listOfNotNull(bodyExpression(success, at)) + success.headers.map(::headerRead)
        return if (arguments.isEmpty()) name else "$name(${arguments.joinToString()})"
    }

    /** What this response's body decodes to, or null where it carries none. */
    private fun bodyExpression(out: Output<*>, at: CallSite): String? = when (out) {
        is JsonOutput<*> -> decodeExpression(out.type, "response.body", at)
        is TextOutput -> "response.body"
        else -> null
    }

    // ------------------------------------------------------------ signatures

    /**
     * What the method declares, and the `request(...)` call that fills it in.
     * [target] is a webhook's destination parameter, or null for an endpoint.
     * Carried here because a failed send names the URL it went to.
     */
    private class Call(val parameters: String, val request: String, val target: String? = null)

    /**
     * What a refusal names the call by, as the expressions the generated method
     * has them under: `Method.GET` and either the path template or, for a
     * webhook, the parameter holding the subscriber's URL.
     */
    private class CallSite(val method: String, val path: String)

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

        // A webhook goes wherever a subscriber registered, so the destination is
        // an argument rather than something the description could carry. Named
        // first, so a declared header called `url` becomes `url2` rather than
        // shadowing it.
        val target = if (ep.webhookName == null) null else unique("url", taken)
        target?.let { required += "$it: String" }

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
            pairs += "${kotlinString(wire)} to ${occurrences(wire, name, listStyle)}"
        }

        val bodyName = unique("body", taken)

        /** One encoded payload and the header naming it. Shared with the negotiated case below. */
        fun encoded(input: BodyInput<*>): String {
            val type = typeFor(checkNotNull(input.payloadType))
            required += "$bodyName: $type"
            // Encoded by core for a form, against the same published schema
            // the server decodes it against — so the caller writes a `SignIn`
            // and the pairs on the wire are the ones that type decodes back
            // from.
            val codec = if (input is FormBody<*>) formCodecName(type) else codecName(type)
            return "body = ClientRequest.Body.Text($codec.encodeToString($bodyName)), " +
                "contentType = ${kotlinString(input.mediaType)}"
        }

        val bodyArgument = when (val input = ep.bodyInput) {
            is JsonBody<*>, is FormBody<*> -> encoded(input)

            // The first alternative, for the same reason the first of several
            // `servers` is the one a client calls: a client has to send exactly
            // one Content-Type, and the document's order is the document's
            // answer. Offering the choice would put a media type parameter on
            // every generated method that takes a body, and a caller who wants
            // the other encoding of a payload they already hold is better
            // served by the server reading both than by this class asking.
            is NegotiatedBody<*> -> encoded(input.alternatives.first())

            // A text part is a typed parameter like any other; a file part is
            // an UploadedFile, which is the same value a handler is given on
            // the other side of the wire. In `partsInWireOrder`, since a
            // streamed part is where the server stops reading.
            is MultipartBody -> {
                val fields = mutableListOf<String>()
                val files = mutableListOf<String>()
                input.partsInWireOrder.forEach { part ->
                    when (part) {
                        is TextPart<*> -> parameter(part.name, part.codec, part.required, null, fields)

                        is FilePart<*> -> {
                            val name = unique(memberName(part.name), taken)
                            if (part.required) required += "$name: UploadedFile"
                            else optional += "$name: UploadedFile? = null"
                            files += "${kotlinString(part.name)} to $name"
                        }
                    }
                }
                "multipart = multipart(" +
                    "fields = listOf(${fields.joinToString(", ")}), " +
                    "files = listOf(${files.joinToString(", ")}))"
            }

            is RawBody -> {
                required += "$bodyName: InputStream"
                "body = ClientRequest.Body.Streaming { $bodyName }, " +
                    "contentType = \"application/octet-stream\""
            }

            null -> null
        }

        ep.queries.forEach { parameter(it.name, it.codec, it.required, it.listStyle, queryPairs) }
        ep.headerParams.forEach { parameter(it.name, it.codec, it.required, it.listStyle, headerPairs) }
        ep.cookieParams.forEach { parameter(it.name, it.codec, it.required, it.listStyle, cookiePairs) }

        val arguments = buildList {
            add("Method.${ep.method.name}")
            // A webhook has no path: the URL it was given is the whole address,
            // and appending anything to it would be this client inventing a
            // route on a host it does not own.
            add(if (target == null) pathExpression(ep, pathNames) else kotlinString(""))
            // An operation the document said is served elsewhere is called
            // there, not at this client's base URL. Baked in rather than
            // offered as a parameter: it is what the description says, the same
            // way the path is, and a caller who wants to point the whole client
            // somewhere else has `baseUrl` for that.
            if (target == null) {
                operationOrigin(ep)?.let { add("origin = ${kotlinString(it)}") }
            } else {
                add("origin = $target")
                // The standing headers are what this client presents to the
                // API — a bearer token, most often — and a subscriber's
                // endpoint is not the API. Sending them would hand the
                // service's own credential to a third party who asked for a
                // notification. What a receiver wants instead is declared as a
                // header on the webhook, and arrives as a typed parameter.
                add("standingHeaders = emptyMap()")
            }
            if (queryPairs.isNotEmpty()) add("query = listOf(${queryPairs.joinToString(", ")})")
            if (headerPairs.isNotEmpty()) add("headerParams = listOf(${headerPairs.joinToString(", ")})")
            if (cookiePairs.isNotEmpty()) add("cookies = listOf(${cookiePairs.joinToString(", ")})")
            bodyArgument?.let { add(it) }
            // A stream is meant to stay open, and the client's deadline bounds
            // the whole exchange on one of the three transports.
            if (streams(ep)) add("deadline = null")
        }

        return Call(
            parameters = (required + optional).joinToString(", "),
            request = "request(${arguments.joinToString(", ")})",
            target = target,
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
    private fun occurrences(wire: String, name: String, listStyle: ListStyle?): String =
        when (val separator = listStyle?.separator) {
            null -> name
            else -> "joined(${kotlinString(wire)}, $name, ${kotlinString(separator.toString())})"
        }

    /**
     * Where this operation is served, where that is not where the API is.
     *
     * The first of its declared servers, trimmed as the constructor trims the
     * one it is given: a client sends one request to one host, and the
     * document's order is the document's answer to which.
     */
    private fun operationOrigin(ep: Endpoint<*, *>): String? =
        ep.servers.firstOrNull()?.trimEnd('/')

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

    /** Whether the response is read as it arrives rather than after it has all arrived. */
    private fun streams(ep: Endpoint<*, *>): Boolean =
        declaredSuccesses(ep).first().let { isStream(it) || it is ByteStreamOutput }

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

    /** The successful responses this endpoint declares: several where it names them, else its output. */
    private fun declaredSuccesses(ep: Endpoint<*, *>): List<Output<*>> =
        (ep.output as? FallibleOutput<*, *>)?.successes ?: listOf(ep.output)

    private fun declaredFailures(ep: Endpoint<*, *>): List<ErrorOutput<*>> =
        (ep.output as? FallibleOutput<*, *>)?.failures.orEmpty()

    /**
     * The sealed type an endpoint's several successes become. One member per
     * declared status, named after it, carrying that status's payload and a
     * property per header it was declared to send — the same shape [failureType]
     * builds, because it is the same problem: a status the caller has to name
     * before it can read what came with it.
     *
     * A `when` over this is exhaustive, so an endpoint that later declares a
     * third 2xx stops the callers that do not handle it from compiling. That is
     * the whole reason it is a sealed type rather than the payloads' common
     * supertype: `Any` would compile everywhere and say nothing.
     */
    private fun resultType(ep: Endpoint<*, *>, successes: List<Output<*>>): String {
        val name = typeName(ep.operationName) + "Result"
        results[name] = buildString {
            appendLine(kdoc("What `${memberName(ep.operationName)}` answers with.", ""))
            appendLine("sealed interface $name {")
            appendLine("    val status: Int")
            successes.forEach { success ->
                appendLine()
                val properties = listOfNotNull(bodyProperty(success)) + success.headers.map(::headerProperty)
                val member = successMember(success.status)
                // A response with no body and no headers has nothing to hold,
                // and Kotlin has no data class without a property — so it is
                // the one value it will ever be.
                val declaration =
                    if (properties.isEmpty()) "data object $member"
                    else "data class $member(${properties.joinToString()})"
                appendLine("    $declaration : $name {")
                appendLine("        override val status: Int get() = ${success.status}")
                appendLine("    }")
            }
            append("}")
        }
        return name
    }

    /** What this response carries, as a property, or null where it carries no body. */
    private fun bodyProperty(out: Output<*>): String? = when (out) {
        is JsonOutput<*> -> "val body: ${typeFor(out.type)}"
        is TextOutput -> "val body: String"
        else -> null
    }

    /**
     * The sealed type an endpoint's declared failures become. One member per
     * declared status, named after it, carrying the payload that status was
     * declared with — and a property per header that status was declared to
     * send, so a `Retry-After` reaches the caller as a number rather than as
     * something to go and dig out of the response.
     */
    private fun failureType(ep: Endpoint<*, *>, declared: List<ErrorOutput<*>>): String {
        val name = typeName(ep.operationName) + "Failure"
        failures[name] = buildString {
            appendLine(kdoc("What `${memberName(ep.operationName)}` declares it can fail with.", ""))
            appendLine("sealed interface $name {")
            appendLine("    val status: Int")
            declared.forEach { failure ->
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

    /**
     * A decode that says which call it was decoding for. The status comes off
     * the response rather than the declaration, so a 404 that arrived as a
     * gateway's HTML is reported as the 404 it was.
     */
    private fun decodeExpression(type: KType, from: String, at: CallSite): String =
        "${codecName(typeFor(type))}.decoded($from, ${at.method}, ${at.path}, response.status)"

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
            if (ep.webhookName == null) {
                add("`${ep.method.name} ${ep.pathSpec.template}`")
                // Said here because it is the surprise: every other method on
                // this class goes to the base URL the caller passed, and this
                // one does not, whatever they passed.
                operationOrigin(ep)?.let {
                    add("Served from $it, which this operation declares rather than the API.")
                }
            } else {
                add("`${ep.method.name}` to [url] — the `${ep.webhookName}` webhook, which this service sends.")
                add("")
                // Hand-wrapped: `kdoc` writes one line per line it is given, and
                // a paragraph handed over whole would come out as one very long
                // one in somebody's editor.
                addAll(
                    listOf(
                        "The destination is a subscriber's, so this client's base URL is not used —",
                        "and neither are its standing headers, which are the credential it presents",
                        "to the API. A subscriber is not the API. What a receiver expects is declared",
                        "on the webhook and arrives here as a parameter.",
                        "",
                        "The response below is the one the *receiver* sends back, which is the part of",
                        "this description nobody publishing it controls.",
                    ),
                )
            }
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
        // A webhook inherits nothing: the document's requirement is what a
        // caller of *this* API presents, and a webhook is presented to a
        // subscriber. Where it says nothing, nothing is known.
        val required: List<SecurityRequirement> =
            if (ep.webhookName != null) ep.security.orEmpty() else ep.security ?: spec.security
        if (required.isEmpty()) return null
        return "Requires: " + required.joinToString(", or ") { requirement ->
            requirement.scheme.name +
                if (requirement.scopes.isEmpty()) "" else " (${requirement.scopes.joinToString(" ")})"
        }
    }
}

// ------------------------------------------------------------------ helpers

/** The member name for a declared success's status: 202 -> `Accepted`. */
private fun successMember(status: Int): String = when (status) {
    200 -> "Ok"
    201 -> "Created"
    202 -> "Accepted"
    203 -> "NonAuthoritative"
    204 -> "NoContent"
    205 -> "ResetContent"
    206 -> "PartialContent"
    else -> "Status$status"
}

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

private fun resource(name: String): String = template(KotlinClientEmitter::class.java, name)

private val IMPORTS = """
    import io.github.matthewjones372.pelican.BodyCodec
    import io.github.matthewjones372.pelican.ClientRequest
    import io.github.matthewjones372.pelican.ClientResponse
    import io.github.matthewjones372.pelican.ClientTransport
    import io.github.matthewjones372.pelican.Codecs
    import io.github.matthewjones372.pelican.Method
    import io.github.matthewjones372.pelican.UploadedFile
    import io.github.matthewjones372.pelican.formCodec
    import java.io.BufferedReader
    import java.io.ByteArrayInputStream
    import java.io.InputStream
    import java.io.Reader
    import java.io.SequenceInputStream
    import java.net.URLEncoder
    import java.nio.charset.StandardCharsets
    import java.time.Duration
    import java.util.Collections
    import java.util.UUID
    import java.util.concurrent.CompletionException
    import kotlin.reflect.typeOf
""".trimIndent()

/** The column the generated banner comments rule out to. */
private const val BANNER_WIDTH = 72

/** A banner for a very long title still gets a rule, even a short one. */
private const val MIN_BANNER_RULE = 3
