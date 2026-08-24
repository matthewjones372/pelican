package dev.pelican

import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** A non-2xx response declared for documentation purposes. */
class ErrorSpec @PublishedApi internal constructor(
    /**
     * Null is OpenAPI's `default`: "and anything else". It is the one response
     * an endpoint can describe and cannot produce — a handler answers with a
     * status, and there is no status that means "some other status" — so a
     * null here only ever arrives from [EndpointBuilder.defaultResponse] or
     * [EndpointBuilder.defaultJson], neither of which hands anything back to
     * return. [ErrorOutput.status], the one a handler names, stays an `Int`.
     */
    val status: Int?,
    val description: String,
    val type: KType?,
    /**
     * Headers this failure carries — `Retry-After` on a 429, chiefly. Set
     * either way it is declared: by `errorResponse(...)` for a failure that is
     * only documented, and by `errorJson(...)` for one a handler returns.
     */
    val headers: List<ResponseHeader<*>> = emptyList(),
)

/**
 * A description of one HTTP endpoint. A plain value: it does no work, holds no
 * handler, and mentions no server library. Interpreters turn it into a route,
 * an OpenAPI operation, or a client call.
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
    /**
     * What the endpoint promises to send back besides the body. Documented on
     * the success response, and the only headers a handler may [Params.setHeader].
     */
    val responseHeaders: List<ResponseHeader<*>>,
    val summary: String?,
    val description: String?,
    val operationId: String?,
    val tags: List<String>,
    val deprecated: Boolean,
    /**
     * Kept off the OpenAPI document. Still routed, still callable, still
     * bound to a typed handler — the description simply is not published.
     */
    val hidden: Boolean,
    /**
     * What a caller must present. Null means "whatever the API says by
     * default"; an empty list means this endpoint is deliberately public even
     * when the API has a default. Documentation only — nothing here checks a
     * token.
     */
    val security: List<SecurityRequirement>?,

    /**
     * Where this one operation is served from, when that is not where the rest
     * of the API is — an upload host, a read replica, a service being moved
     * one route at a time. Empty means the API's own [ApiSpec.servers], which
     * is what almost every endpoint says.
     *
     * Routing ignores it entirely, and has to: a server serves what it serves,
     * and a description that could redirect a route would be a description
     * that decides where requests land. What reads it is the document, which
     * publishes `servers` on the operation, and a generated client, which sends
     * that operation's calls there instead of to its own base URL. Same list
     * as [ApiSpec.servers], first entry first, for the same reason: a client
     * has to pick one, and the document's order is the document's answer.
     */
    val servers: List<String>,

    /**
     * The name of the [Webhook] this describes, or null — which is what every
     * endpoint that is a route says.
     *
     * A description of a call the service *sends* is the same description read
     * in the other direction, so it is one of these rather than a second model.
     * What the name is for is telling the two apart afterwards: [Api] refuses to
     * bind one, so a webhook cannot become a route by being put in the wrong
     * list, and [operationName] derives a name from it rather than from a path
     * that is deliberately empty.
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

    /**
     * Leaves this endpoint out of the OpenAPI document. It is still routed and
     * still served — this hides the description, it does not close the door.
     */
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
        // Inputs listed on endpoint(...) register themselves, so each one is
        // written down exactly once.
        declared.forEach { key ->
            when (key) {
                is QueryParam<*> -> queries += key

                is HeaderParam<*> -> headerParams += key

                is CookieParam<*> -> cookieParams += key

                is BodyInput<*> -> bodyInput = key

                // A part is an input in its own right; the envelope holding
                // them is assembled below, once they are all known.
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

    /**
     * Declares headers this endpoint sends back. They appear on the success
     * response in the document, and a handler may set exactly these:
     *
     * ```
     * emits(location, rateLimitRemaining)
     * ```
     */
    fun emits(vararg headers: ResponseHeader<*>) { responseHeaders += headers }

    // ------------------------------------------------------------ security

    /**
     * Requires [scheme], optionally with [scopes]:
     *
     * ```
     * val placeOrder = endpoint(userId, newOrder) {
     *     post("users" / userId / "orders")
     *     security(oauth, "orders:write")
     *     json<Order>(status = 201)
     * }
     * ```
     *
     * A scope the scheme never declared fails here, when the endpoint value is
     * built. Calling this twice records two alternatives — OpenAPI reads a list
     * of requirements as "any one of these".
     */
    fun security(scheme: SecurityScheme, vararg scopes: String) {
        securityRequirements = securityRequirements.orEmpty() + SecurityRequirement(scheme, scopes.toList())
    }

    /**
     * Marks this endpoint as open, overriding the API-wide requirement — the
     * login route, a health check.
     */
    fun noSecurity() { securityRequirements = emptyList() }

    fun errorResponse(status: Int, description: String, vararg headers: ResponseHeader<*>) {
        errors += ErrorSpec(status, description, null, headers.toList())
    }

    /**
     * Documents OpenAPI's `default` response — "and anything else":
     *
     * ```
     * defaultResponse("Any other failure, as an ApiError")
     * ```
     *
     * This is the one response an endpoint describes and cannot produce.
     * Everything else declared here is either a status a handler returns or a
     * status a handler throws, and both are statuses; `default` is the absence
     * of one. So it returns nothing to name: there is no value to pass to
     * [orFail], nothing binds it, and no handler can answer with it. What it
     * does is tell a reader of the document what the statuses this endpoint
     * did not enumerate will look like when they arrive.
     *
     * The alternative was to keep refusing it on import and have no way to
     * write one, which cost every document that says "and any other error is a
     * Problem" the fact that it says so — a fact a client generator would
     * otherwise have to invent.
     */
    fun defaultResponse(description: String, vararg headers: ResponseHeader<*>) {
        errors += ErrorSpec(null, description, null, headers.toList())
    }

    /**
     * The same, for a `default` that carries a JSON payload:
     *
     * ```
     * defaultJson<ApiError>("Any other failure")
     * ```
     *
     * Named after [errorJson] and deliberately unlike it in the one way that
     * matters: [errorJson] hands back a declaration a handler can return, and
     * this hands back nothing, because a `default` is not a response anything
     * can produce. [T] is published as that response's schema and no more.
     */
    inline fun <reified T> defaultJson(description: String, vararg headers: ResponseHeader<*>) {
        addDefault(typeOf<T>(), description, headers.toList())
    }

    @PublishedApi
    internal fun addDefault(type: KType, description: String, headers: List<ResponseHeader<*>>) {
        errors += ErrorSpec(null, description, type, headers)
    }

    /**
     * Declares a failure response carrying [T] as a JSON body.
     *
     * Used as a statement it documents the failure and nothing more. Pass the
     * value it returns to [orFail] instead and the failure becomes part of the
     * output type, so the handler must return it rather than throw it:
     *
     * ```
     * json<User>() orFail errorJson<Problem>(404, "No user with that id")
     * ```
     *
     * A failure the handler names has to be nameable, so one shared by several
     * endpoints is better declared as a top-level `val` with the [errorJson]
     * function of the same name.
     *
     * [headers] are the ones this failure sends with its payload — a
     * `Retry-After` on a 429. They are documented on that response, and the
     * handler supplies their values when it returns the failure.
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
     * [headers] belong to *this* response rather than to the endpoint, and are
     * only nameable where the handler names the response it is producing —
     * which is to say where the endpoint declares more than one. Use
     * `emits(...)` for a header every response carries.
     */
    inline fun <reified T> json(status: Int = 200, vararg headers: ResponseHeader<*>): JsonOutput<T> =
        JsonOutput(status, typeOf<T>(), headers.toList())

    /** Newline-delimited JSON. Handler produces the backend's stream of `T`. */
    inline fun <reified T> ndjson(status: Int = 200): NdjsonOutput<T> =
        NdjsonOutput(status, typeOf<T>())

    /**
     * A streamed JSON array. Renders as `[{...},{...}]` with the elements
     * flushed as they are produced — Pekko implements the framing with
     * `EntityStreamingSupport.json()`.
     */
    inline fun <reified T> jsonArray(status: Int = 200): JsonArrayOutput<T> =
        JsonArrayOutput(status, typeOf<T>())

    /** Server-sent events. Handler produces the backend's stream of `T`. */
    inline fun <reified T> sse(status: Int = 200, eventName: String? = null): SseOutput<T> =
        SseOutput(status, typeOf<T>(), eventName)

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
 * Describes an endpoint, declaring its inputs by listing them.
 *
 * ```
 * val streamOrders = endpoint(userId, limit, statusFilter) {
 *     get("users" / userId / "orders")
 *     ndjson<Order>()
 * }
 *
 * streamOrders streamedNow { (id, lim, status) -> ... }   // (Long, Int, OrderStatus?)
 * ```
 *
 * Listing an input here does three things at once: it registers the parameter
 * for decoding, registers it for documentation, and fixes the handler's
 * signature. The handler receives exactly the declared inputs, with their
 * declared types — reading anything else is not a runtime error, it does not
 * compile.
 *
 * There is one overload per arity up to six. Past that a tuple stops paying for
 * itself; drop to the lens form below.
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
 * Describes an endpoint in lens style: the handler receives the whole [Params]
 * bag and reads it by key, with inputs registered by `query(...)`/`header(...)`
 * inside the block. More flexible past five or six inputs, at the cost of the
 * compile-time guarantee — reading an undeclared key throws at request time
 * rather than failing to compile.
 */
fun <R> endpoint(block: EndpointBuilder.() -> Output<R>): Endpoint<Params, R> =
    describe(lensInputs, block)

/**
 * Describes an endpoint with an [Inputs] built elsewhere — [noInputs] for an
 * endpoint that reads nothing, or a projection of your own.
 */
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
 * The same, for the description of a call the service sends. It goes through
 * [build] rather than beside it because a webhook is an endpoint description —
 * the differences are the two lines below and the checks [webhook] runs after,
 * and a second builder would have been the whole DSL copied to change them.
 *
 * The lens inputs are not a limitation being worked around: see [webhook].
 */
internal fun <R> describeWebhook(
    name: String,
    method: Method,
    block: EndpointBuilder.() -> Output<R>,
): Endpoint<Params, R> {
    val b = EndpointBuilder(lensInputs.keys)
    // Set before the block, so that a block calling `post(...)` overwrites it
    // and is caught saying what it may not say, rather than silently winning.
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
    // A failure declared outside the block — a top-level val shared by several
    // endpoints — is documented here, when the output that names it is seen.
    // One declared with errorJson(...) inside the block is already recorded.
    if (out is FallibleOutput<*, *>) {
        out.failures
            .filterNot { declared -> b.declaredFailures.any { it === declared } }
            .forEach { b.errors += it.spec() }
    }

    // The parts an endpoint declares *are* its body, so the envelope is built
    // here rather than written down. Declaring a body as well would leave two
    // things claiming to be one, and there is no reading of that which is not
    // a mistake — hence the require rather than a rule about which wins.
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
 * Catches the mismatches the type system can't see: an input declared but not
 * present in the path, or a path capture nobody can read. Runs when the
 * endpoint value is created, so it fails at class-init time, not on the first
 * request.
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

        // Reading stops at the first file part, because handing one over as a
        // stream is the whole point and a second one could only be reached by
        // buffering the first. A second declaration would therefore describe a
        // part no handler could ever be given.
        if (body.fileParts.size > 1) {
            error(
                "$ep declares ${body.fileParts.size} file parts, and only the first could be streamed. " +
                    "Take the rest as separate requests, or as one rawBody() you parse yourself.",
            )
        }
    }

    // `default` is one key in OpenAPI's response map, so a second declaration
    // would not be published beside the first — it would replace it, and the
    // endpoint would say something nobody wrote.
    if (ep.errors.count { it.status == null } > 1) {
        error(
            "$ep declares more than one default response, and a document has room for one: " +
                "`default` is the single entry meaning \"and anything else\". Say it once, or give the " +
                "others the statuses they really are.",
        )
    }

    // Two declarations of one header name would leave a handler unable to say
    // which it meant, and the document with two entries for one header.
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
 * A header declared on one response is supplied where that response is named,
 * and a handler only names a response when there is more than one to choose
 * between. Declared on an endpoint's *only* output it is a promise nothing
 * could keep — the handler returns the payload alone and never sees the
 * declaration — so it is refused here rather than published and never sent.
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
 * What interpreters call this operation: the declared [Endpoint.operationId],
 * or a name derived from the method and the path.
 *
 * Shared deliberately. The OpenAPI document's `operationId` and a generated
 * client's method name are the same string, so a caller reading one and calling
 * the other cannot be looking at two different names for the same endpoint.
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
 * How many values a request for this endpoint will decode, for sizing the bag
 * they go into. `LinkedHashMap()` with no argument allocates sixteen buckets
 * on first insert, which is most of a request's worth of allocation for an
 * endpoint that declares two.
 *
 * Capacity, not size: a hash map resizes at three quarters full, so asking for
 * exactly what will be put in it would grow it on the last insert.
 */
fun Endpoint<*, *>.declaredInputCount(): Int {
    val declared = pathSpec.captures.size + queries.size + headerParams.size +
        cookieParams.size + (if (bodyInput == null) 0 else 1)
    return if (declared == 0) 1 else declared * INVERSE_LOAD_FACTOR_NUMERATOR / INVERSE_LOAD_FACTOR_DENOMINATOR + 1
}

/** A hash map grows at three quarters full; 4/3 of what goes in is what to ask for. */
private const val INVERSE_LOAD_FACTOR_NUMERATOR = 4
private const val INVERSE_LOAD_FACTOR_DENOMINATOR = 3
