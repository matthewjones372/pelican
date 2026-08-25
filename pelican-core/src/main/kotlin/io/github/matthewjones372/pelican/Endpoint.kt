package io.github.matthewjones372.pelican

import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration

/** A non-2xx response declared for documentation purposes. */
class ErrorSpec @PublishedApi internal constructor(
    /** Null is OpenAPI's `default`: "and anything else", which no handler can name. */
    val status: Int?,
    val description: String,
    val type: KType?,
    /** Headers this failure carries — `Retry-After` on a 429, chiefly. */
    val headers: List<ResponseHeader<*>> = emptyList(),
)

/**
 * A description of one HTTP endpoint. A plain value: no handler, no server
 * library. Interpreters turn it into a route, an OpenAPI operation, or a
 * client call.
 */
@Suppress("LongParameterList") // A description record: every parameter is a facet of the description.
class Endpoint<I, R> internal constructor(
    val inputs: Inputs<I>,
    val method: Method,
    val pathSpec: PathSpec,
    val queries: List<QueryParam<*>>,
    val headerParams: List<HeaderParam<*>>,
    val cookieParams: List<CookieParam<*>>,
    val bodyInput: BodyInput<*>?,
    val output: Output<R>,
    val errors: List<ErrorSpec>,
    /** The only headers a handler may [Params.setHeader]. */
    val responseHeaders: List<ResponseHeader<*>>,
    val summary: String?,
    val description: String?,
    val operationId: String?,
    val tags: List<String>,
    val deprecated: Boolean,
    /** Kept off the OpenAPI document. Still routed and still callable. */
    val hidden: Boolean,
    /**
     * Null means the API's default; empty means deliberately public despite
     * one. Documentation only — nothing here checks a token.
     */
    val security: List<SecurityRequirement>?,

    /**
     * Where this one operation is served from — an upload host, a read
     * replica. Empty means the API's own [ApiSpec.servers].
     *
     * Routing ignores it: a description that could redirect a route would be a
     * description deciding where requests land. Only the document and a
     * generated client read it, and a client takes the first entry.
     */
    val servers: List<String>,

    /**
     * The name of the [Webhook] this describes, or null for a route.
     *
     * A call the service *sends* is the same description read the other way,
     * so it is one of these rather than a second model. The name is what tells
     * the two apart: [Api] refuses to bind one.
     */
    val webhookName: String? = null,
) {
    override fun toString() =
        if (webhookName == null) "$method ${pathSpec.template}" else "webhook $webhookName ($method)"
}

/** Receiver for the [endpoint] DSL. */
@Suppress("TooManyFunctions") // One function per input and output kind — that list is the DSL.
class EndpointBuilder internal constructor(declared: List<ParamKey<*>>) {
    var summary: String? = null
    var description: String? = null
    var operationId: String? = null
    var deprecated: Boolean = false

    /** Hides the description, not the route: it is still served. */
    var hidden: Boolean = false

    internal var method: Method = Method.GET
    internal var pathSpec: PathSpec = PathSpec.root
    internal val queries = mutableListOf<QueryParam<*>>()
    internal val headerParams = mutableListOf<HeaderParam<*>>()
    internal val cookieParams = mutableListOf<CookieParam<*>>()
    internal val parts = mutableListOf<MultipartPart<*>>()
    internal var partsDescription: String? = null
    internal var bodyInput: BodyInput<*>? = null
    internal val tagList = mutableListOf<String>()
    internal val errors = mutableListOf<ErrorSpec>()
    internal val responseHeaders = mutableListOf<ResponseHeader<*>>()
    internal val serverUrls = mutableListOf<String>()

    /** The typed failures declared here, so [orFail] does not document them twice. */
    @PublishedApi
    internal val declaredFailures = mutableListOf<ErrorOutput<*>>()
    internal var securityRequirements: List<SecurityRequirement>? = null

    init {
        // Inputs listed on endpoint(...) register themselves, so each is
        // written down exactly once.
        declared.forEach { key ->
            when (key) {
                is QueryParam<*> -> queries += key

                is HeaderParam<*> -> headerParams += key

                is CookieParam<*> -> cookieParams += key

                is BodyInput<*> -> bodyInput = key

                // The envelope holding them is assembled below, once they are
                // all known.
                is MultipartPart<*> -> parts += key

                is PathParam<*> -> Unit // matched positionally from the path
            }
        }
    }

    // ------------------------------------------------------------ method + path

    fun get(p: PathSpec) = route(Method.GET, p)
    fun get(p: String) = route(Method.GET, path(p))
    fun get(p: PathParam<*>) = route(Method.GET, path(p))

    fun post(p: PathSpec) = route(Method.POST, p)
    fun post(p: String) = route(Method.POST, path(p))

    fun put(p: PathSpec) = route(Method.PUT, p)
    fun put(p: String) = route(Method.PUT, path(p))

    fun patch(p: PathSpec) = route(Method.PATCH, p)
    fun patch(p: String) = route(Method.PATCH, path(p))

    fun delete(p: PathSpec) = route(Method.DELETE, p)
    fun delete(p: String) = route(Method.DELETE, path(p))

    fun route(m: Method, p: PathSpec) {
        method = m
        pathSpec = p
    }

    // ------------------------------------------------------------ where it is served

    /**
     * Says this operation is served from somewhere other than the rest of the
     * API:
     *
     * ```
     * val uploadOrders = endpoint(userId, importFile) {
     *     post("users" / userId / "orders" / "import")
     *     servers("https://uploads.example.com")
     *     json<ImportResult>(status = 201)
     * }
     * ```
     *
     * Documentation, and a client's problem. Nothing about routing changes:
     * this server answers the paths it is given, and an endpoint that could
     * move a route to another host would be a description deciding where a
     * request lands. What honours it is [ApiSpec.openApi], which publishes
     * `servers` on the operation, and a generated client, which sends this
     * operation there rather than to its own base URL.
     *
     * Several are allowed because OpenAPI allows them and a document is worth
     * republishing as it was read. A client takes the first, as it does with
     * the API's own list.
     */
    fun servers(vararg urls: String) {
        urls.forEach { url ->
            require(url.isNotBlank()) {
                "A server URL is where calls to this endpoint go, so it has to be one: " +
                    "servers(\"https://uploads.example.com\"). Leave the call out for an endpoint served " +
                    "where the rest of the API is."
            }
        }
        serverUrls += urls
    }

    // ------------------------------------------------------------ inputs

    fun query(vararg params: QueryParam<*>) { queries += params }
    fun header(vararg params: HeaderParam<*>) { headerParams += params }
    fun cookie(vararg params: CookieParam<*>) { cookieParams += params }

    /**
     * Declares the parts of a `multipart/form-data` body:
     *
     * ```
     * part(caption, photo, description = "The picture and what to call it")
     * ```
     *
     * The envelope itself is not a value anyone writes down — listing its
     * parts is what says the body is one.
     */
    fun part(vararg params: MultipartPart<*>, description: String? = null) {
        parts += params
        if (description != null) partsDescription = description
    }

    fun <T> body(input: BodyInput<T>): BodyInput<T> {
        bodyInput = input
        return input
    }

    fun tag(vararg names: String) { tagList += names }

    // ------------------------------------------------------------ outputs, besides the body

    /** Declares headers this endpoint sends back; a handler may set exactly these. */
    fun emits(vararg headers: ResponseHeader<*>) { responseHeaders += headers }

    // ------------------------------------------------------------ security

    /**
     * Requires [scheme], optionally with [scopes]. A scope the scheme never
     * declared fails here. Calling this twice records two alternatives, which
     * is how OpenAPI reads a list of requirements.
     */
    fun security(scheme: SecurityScheme, vararg scopes: String) {
        securityRequirements = securityRequirements.orEmpty() + SecurityRequirement(scheme, scopes.toList())
    }

    /** Overrides the API-wide requirement — the login route, a health check. */
    fun noSecurity() { securityRequirements = emptyList() }

    fun errorResponse(status: Int, description: String, vararg headers: ResponseHeader<*>) {
        errors += ErrorSpec(status, description, null, headers.toList())
    }

    /**
     * Documents OpenAPI's `default` response — "and anything else".
     *
     * The one response an endpoint describes and cannot produce, so it returns
     * nothing to name and nothing binds it.
     */
    fun defaultResponse(description: String, vararg headers: ResponseHeader<*>) {
        errors += ErrorSpec(null, description, null, headers.toList())
    }

    /**
     * The same, for a `default` carrying a JSON payload. Unlike [errorJson] it
     * returns nothing: [T] is published as the schema and no more.
     */
    inline fun <reified T> defaultJson(description: String, vararg headers: ResponseHeader<*>) {
        addDefault(typeOf<T>(), description, headers.toList())
    }

    @PublishedApi
    internal fun addDefault(type: KType, description: String, headers: List<ResponseHeader<*>>) {
        errors += ErrorSpec(null, description, type, headers)
    }

    /**
     * Declares a failure carrying [T] as a JSON body. As a statement it only
     * documents; passed to [orFail] it joins the output type, so the handler
     * returns it rather than throwing it.
     *
     * [headers] travel with this failure's payload and the handler supplies
     * their values.
     */
    inline fun <reified T> errorJson(
        status: Int,
        description: String,
        vararg headers: ResponseHeader<*>,
    ): ErrorOutput<T> = addError(ErrorOutput(status, typeOf<T>(), description, headers.toList()))

    @PublishedApi
    internal fun <T> addError(failure: ErrorOutput<T>): ErrorOutput<T> {
        declaredFailures += failure
        errors += failure.spec()
        return failure
    }

    // ------------------------------------------------------------ outputs

    /**
     * A single JSON value. Handler produces `T`.
     *
     * [headers] belong to this response alone, so they are only settable where
     * the handler names it. Use `emits(...)` for one every response carries.
     */
    inline fun <reified T> json(status: Int = 200, vararg headers: ResponseHeader<*>): JsonOutput<T> =
        JsonOutput(status, typeOf<T>(), headers.toList())

    /** Newline-delimited JSON. Handler produces the backend's stream of `T`. */
    inline fun <reified T> ndjson(status: Int = 200): NdjsonOutput<T> =
        NdjsonOutput(status, typeOf<T>())

    /** A streamed JSON array, flushed as elements are produced. */
    inline fun <reified T> jsonArray(status: Int = 200): JsonArrayOutput<T> =
        JsonArrayOutput(status, typeOf<T>())

    /**
     * Server-sent events. Handler produces the backend's stream of `T`.
     * [keepAlive] fills an idle stream; see [SseOutput.keepAlive].
     */
    inline fun <reified T> sse(
        status: Int = 200,
        eventName: String? = null,
        keepAlive: Duration? = null,
    ): SseOutput<T> = SseOutput(status, typeOf<T>(), eventName, keepAlive)

    /** An opaque byte stream. Handler produces the backend's stream of bytes. */
    fun bytes(mediaType: String = "application/octet-stream", status: Int = 200): ByteStreamOutput =
        ByteStreamOutput(status, mediaType)

    /** Plain text. [headers] as on [json]. */
    fun text(status: Int = 200, vararg headers: ResponseHeader<*>): TextOutput =
        TextOutput(status, headers.toList())

    /** No body at all — a 204, or the `202 Accepted` beside a `200`. [headers] as on [json]. */
    fun empty(status: Int = 204, vararg headers: ResponseHeader<*>): EmptyOutput =
        EmptyOutput(status, headers.toList())
}

/**
 * Describes an endpoint, declaring its inputs by listing them. Listing one
 * registers it for decoding, for documentation, and in the handler's
 * signature at once, so reading an undeclared input does not compile.
 *
 * One overload per arity up to six; past that use the lens form below.
 */
fun <A, R> endpoint(
    a: ParamKey<A>,
    block: EndpointBuilder.() -> Output<R>,
): Endpoint<A, R> = describe(
    Inputs(listOf(a), { p -> p[a] }, { i -> mapOf(a to i) }),
    block,
)

fun <A, B, R> endpoint(
    a: ParamKey<A>, b: ParamKey<B>,
    block: EndpointBuilder.() -> Output<R>,
): Endpoint<In2<A, B>, R> = describe(
    Inputs(listOf(a, b), { p -> In2(p[a], p[b]) }, { i -> mapOf(a to i.a, b to i.b) }),
    block,
)

fun <A, B, C, R> endpoint(
    a: ParamKey<A>, b: ParamKey<B>, c: ParamKey<C>,
    block: EndpointBuilder.() -> Output<R>,
): Endpoint<In3<A, B, C>, R> = describe(
    Inputs(
        listOf(a, b, c),
        { p -> In3(p[a], p[b], p[c]) },
        { i -> mapOf(a to i.a, b to i.b, c to i.c) },
    ),
    block,
)

fun <A, B, C, D, R> endpoint(
    a: ParamKey<A>, b: ParamKey<B>, c: ParamKey<C>, d: ParamKey<D>,
    block: EndpointBuilder.() -> Output<R>,
): Endpoint<In4<A, B, C, D>, R> = describe(
    Inputs(
        listOf(a, b, c, d),
        { p -> In4(p[a], p[b], p[c], p[d]) },
        { i -> mapOf(a to i.a, b to i.b, c to i.c, d to i.d) },
    ),
    block,
)

fun <A, B, C, D, E, R> endpoint(
    a: ParamKey<A>, b: ParamKey<B>, c: ParamKey<C>, d: ParamKey<D>, e: ParamKey<E>,
    block: EndpointBuilder.() -> Output<R>,
): Endpoint<In5<A, B, C, D, E>, R> = describe(
    Inputs(
        listOf(a, b, c, d, e),
        { p -> In5(p[a], p[b], p[c], p[d], p[e]) },
        { i -> mapOf(a to i.a, b to i.b, c to i.c, d to i.d, e to i.e) },
    ),
    block,
)

fun <A, B, C, D, E, F, R> endpoint(
    a: ParamKey<A>, b: ParamKey<B>, c: ParamKey<C>, d: ParamKey<D>, e: ParamKey<E>, f: ParamKey<F>,
    block: EndpointBuilder.() -> Output<R>,
): Endpoint<In6<A, B, C, D, E, F>, R> = describe(
    Inputs(
        listOf(a, b, c, d, e, f),
        { p -> In6(p[a], p[b], p[c], p[d], p[e], p[f]) },
        { i -> mapOf(a to i.a, b to i.b, c to i.c, d to i.d, e to i.e, f to i.f) },
    ),
    block,
)

/**
 * Lens style: the handler reads the whole [Params] bag by key. More flexible
 * past five or six inputs, at the cost of the compile-time guarantee —
 * an undeclared key throws at request time instead.
 */
fun <R> endpoint(block: EndpointBuilder.() -> Output<R>): Endpoint<Params, R> =
    describe(lensInputs, block)

/** With an [Inputs] built elsewhere — [noInputs], or a projection of your own. */
fun <I, R> endpoint(
    inputs: Inputs<I>,
    block: EndpointBuilder.() -> Output<R>,
): Endpoint<I, R> = describe(inputs, block)

private fun <I, R> describe(
    inputs: Inputs<I>,
    block: EndpointBuilder.() -> Output<R>,
): Endpoint<I, R> {
    val b = EndpointBuilder(inputs.keys)
    val out = b.block()
    return build(inputs, b, out)
}

/**
 * The same, for a call the service sends. Shares [build] because a webhook is
 * an endpoint description; a second builder would have copied the whole DSL to
 * change two lines.
 */
internal fun <R> describeWebhook(
    name: String,
    method: Method,
    block: EndpointBuilder.() -> Output<R>,
): Endpoint<Params, R> {
    val b = EndpointBuilder(lensInputs.keys)
    // Set before the block, so a block calling `post(...)` overwrites it and
    // is caught rather than silently winning.
    b.method = method
    val out = b.block()
    return build(lensInputs, b, out, webhookName = name)
}

private fun <I, R> build(
    inputs: Inputs<I>,
    b: EndpointBuilder,
    out: Output<R>,
    webhookName: String? = null,
): Endpoint<I, R> {
    // A failure declared outside the block is documented here; one declared
    // with errorJson(...) inside it is already recorded.
    if (out is FallibleOutput<*, *>) {
        out.failures
            .filterNot { declared -> b.declaredFailures.any { it === declared } }
            .forEach { b.errors += it.spec() }
    }

    // The parts an endpoint declares are its body, so the envelope is built
    // here. A body as well would leave two things claiming to be one.
    if (b.parts.isNotEmpty()) {
        require(b.bodyInput == null) {
            "An endpoint declaring multipart parts cannot also declare a ${b.bodyInput} — " +
                "the parts are the body."
        }
        b.bodyInput = MultipartBody(b.parts.toList(), b.partsDescription)
    }

    return Endpoint(
        inputs = inputs,
        method = b.method,
        pathSpec = b.pathSpec,
        queries = b.queries.toList(),
        headerParams = b.headerParams.toList(),
        cookieParams = b.cookieParams.toList(),
        bodyInput = b.bodyInput,
        output = out,
        errors = b.errors.toList(),
        responseHeaders = b.responseHeaders.toList(),
        summary = b.summary,
        description = b.description,
        operationId = b.operationId,
        tags = b.tagList.toList(),
        deprecated = b.deprecated,
        hidden = b.hidden,
        security = b.securityRequirements?.toList(),
        servers = b.serverUrls.toList(),
        webhookName = webhookName,
    ).also(::validate)
}

/**
 * Catches what the type system cannot see: an input declared but absent from
 * the path, or a capture nobody reads. Runs at class-init, not first request.
 */
private fun validate(ep: Endpoint<*, *>) {
    val inPath = ep.pathSpec.captures.toSet()
    val declared = ep.inputs.keys.filterIsInstance<PathParam<*>>().toSet()

    if (ep.inputs.keys.isNotEmpty()) {
        (declared - inPath).forEach {
            error("$ep declares path parameter '${it.name}' as an input, but the path is ${ep.pathSpec.template}")
        }
        (inPath - declared).forEach {
            error(
                "$ep captures '${it.name}' in its path but never declares it as an input, " +
                    "so no handler could read it",
            )
        }
    }

    val duplicates = ep.pathSpec.captures.groupBy { it.name }.filterValues { it.size > 1 }.keys
    if (duplicates.isNotEmpty()) error("$ep uses the path parameter name(s) $duplicates more than once")

    val body = ep.bodyInput
    if (body is MultipartBody) {
        val partClashes = body.parts.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (partClashes.isNotEmpty()) error("$ep declares the multipart part(s) $partClashes more than once")

        validateParts(ep, body)
    }

    // `default` is one key in OpenAPI's response map, so a second declaration
    // would replace the first rather than join it.
    if (ep.errors.count { it.status == null } > 1) {
        error(
            "$ep declares more than one default response, and a document has room for one: " +
                "`default` is the single entry meaning \"and anything else\". Say it once, or give the " +
                "others the statuses they really are.",
        )
    }

    // Two declarations of one name leave a handler unable to say which it
    // meant, and the document with two entries for one header.
    val headerClashes = ep.responseHeaders
        .groupBy { it.name.lowercase() }
        .filterValues { it.size > 1 }
        .keys
    if (headerClashes.isNotEmpty()) {
        error("$ep declares the response header(s) $headerClashes more than once")
    }

    validateResponseHeaders(ep)
}

/**
 * Checked where the envelope is described rather than on the request that
 * trips over it: reading stops at a streamed part, so at most one part may be
 * streamed and it must go last. [bufferedFile] is the way out, which is why
 * both messages name it.
 */
private fun validateParts(ep: Endpoint<*, *>, body: MultipartBody) {
    val streamed = body.fileParts.filter { it.streamed }
    if (streamed.size > 1) {
        error(
            "$ep declares ${streamed.size} streamed file parts (${streamed.joinToString { it.name }}), " +
                "and only the first could be streamed: reading stops there. Declare all but one with " +
                "bufferedFile(name, maxBytes = ...), which says what holding it costs, or take the " +
                "envelope as a rawBody() you parse yourself.",
        )
    }

    // Declaration order is what a caller reads the envelope's order off — an
    // HTML form, a curl invocation. The two clients here reorder to protect
    // themselves (`partsInWireOrder`), which does not licence a description of
    // a request no server could read.
    //
    // Any part, not only a file: a text part after the streamed one is read by
    // nobody either, and an optional one fails silently to its default.
    val stream = streamed.singleOrNull() ?: return
    val after = body.parts.dropWhile { it !== stream }.drop(1)
    if (after.isNotEmpty()) {
        error(
            "$ep declares ${after.joinToString { "'${it.name}'" }} after the streamed file part " +
                "'${stream.name}', and reading stops at the streamed one. Declare it last — a part that " +
                "has to come after it is a text part moved in front of it, or a bufferedFile(name, " +
                "maxBytes = ...), which is read as it arrives at the price of saying what holding it costs.",
        )
    }
}

/**
 * A response's own headers are supplied where that response is named, and a
 * handler only names one when there are several. On an endpoint's only output
 * the promise is unkeepable, so it is refused rather than never sent.
 */
private fun validateResponseHeaders(ep: Endpoint<*, *>) {
    val declared = ep.output.let { if (it is FallibleOutput<*, *>) it.successes else listOf(it) }

    if (ep.output !is FallibleOutput<*, *> && ep.output.headers.isNotEmpty()) {
        error(
            "$ep declares ${ep.output.headers.joinToString { it.name }} on its only response, and a handler " +
                "for a single response returns the payload alone, so nothing could supply them. " +
                "Declare them with emits(...) and set them with setHeader, or declare a second response " +
                "so the handler names the one it is producing.",
        )
    }

    declared.forEach { response ->
        val clashes = response.headers.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.keys
        if (clashes.isNotEmpty()) error("$ep declares the header(s) $clashes on $response more than once")
    }
}

/**
 * The declared [Endpoint.operationId], or one derived from method and path.
 * The document's `operationId` and a generated client's method name are the
 * same string on purpose.
 */
val Endpoint<*, *>.operationName: String
    get() = operationId ?: webhookName ?: derivedOperationName(method, pathSpec)

private fun derivedOperationName(method: Method, pathSpec: PathSpec): String {
    val parts = pathSpec.segments.map {
        when (it) {
            is PathSegment.Literal -> it.value.replaceFirstChar(Char::uppercaseChar)
            is PathSegment.Capture -> "By" + it.param.name.replaceFirstChar(Char::uppercaseChar)
        }
    }
    return method.name.lowercase() + parts.joinToString("")
}

/**
 * How many values a request will decode, for sizing the bag they go into.
 * `LinkedHashMap()` with no argument allocates sixteen buckets, which is most
 * of a request's allocation for an endpoint declaring two.
 */
fun Endpoint<*, *>.declaredInputCount(): Int {
    val declared = pathSpec.captures.size + queries.size + headerParams.size +
        cookieParams.size + (if (bodyInput == null) 0 else 1)
    return if (declared == 0) 1 else declared * INVERSE_LOAD_FACTOR_NUMERATOR / INVERSE_LOAD_FACTOR_DENOMINATOR + 1
}

/** A hash map grows at three quarters full; 4/3 of what goes in is what to ask for. */
private const val INVERSE_LOAD_FACTOR_NUMERATOR = 4
private const val INVERSE_LOAD_FACTOR_DENOMINATOR = 3
