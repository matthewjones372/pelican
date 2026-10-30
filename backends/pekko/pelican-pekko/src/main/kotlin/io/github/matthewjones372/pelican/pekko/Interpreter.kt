package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.BodyInput
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.CorsPolicy
import io.github.matthewjones372.pelican.CorsPreflight
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.FormBody
import io.github.matthewjones372.pelican.JsonBody
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.MultipartBody
import io.github.matthewjones372.pelican.NdjsonBody
import io.github.matthewjones372.pelican.NegotiatedBody
import io.github.matthewjones372.pelican.NotAcceptable
import io.github.matthewjones372.pelican.Output
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.PathSegment
import io.github.matthewjones372.pelican.PayloadTooLarge
import io.github.matthewjones372.pelican.RawBody
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.SseOutput
import io.github.matthewjones372.pelican.corsPolicy
import io.github.matthewjones372.pelican.spi.CorsHeaders
import io.github.matthewjones372.pelican.spi.EndpointPlan
import io.github.matthewjones372.pelican.spi.RequestBodyCodecs
import io.github.matthewjones372.pelican.spi.RequestView
import io.github.matthewjones372.pelican.spi.RouteIndex
import io.github.matthewjones372.pelican.spi.Slots
import io.github.matthewjones372.pelican.spi.acceptable
import io.github.matthewjones372.pelican.spi.decode
import io.github.matthewjones372.pelican.spi.decodeCaller
import io.github.matthewjones372.pelican.spi.handlerFor
import io.github.matthewjones372.pelican.spi.ndjsonFrames
import io.github.matthewjones372.pelican.spi.plan
import io.github.matthewjones372.pelican.spi.requestBodyCodec
import io.github.matthewjones372.pelican.spi.responseCodecs
import io.github.matthewjones372.pelican.spi.routeIndex
import io.github.matthewjones372.pelican.spi.succeeded
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.http.javadsl.model.HttpMethods
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.model.HttpResponse
import org.apache.pekko.http.javadsl.model.StatusCodes
import org.apache.pekko.http.javadsl.model.headers.RawHeader
import org.apache.pekko.http.javadsl.server.Directives
import org.apache.pekko.http.javadsl.server.Route
import org.apache.pekko.stream.javadsl.Source
import org.apache.pekko.stream.javadsl.StreamConverters
import org.apache.pekko.util.ByteString
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
/**
 * Pekko's pool for work that blocks, separate from the fork-join pool running
 * actors and stream stages.
 */
private const val BLOCKING_IO_DISPATCHER = "pekko.actor.default-blocking-io-dispatcher"

/**
 * How far past [Api.maxBodyBytes] a body with no declared length is still read,
 * so the 413 goes down a connection that is still whole. See the read itself.
 */
private const val DRAIN_OVERRUN_BYTES: Long = 64L * 1024L

/**
 * Interprets an [Api] as a Pekko HTTP route: each endpoint becomes
 * `method { extractRequest { ... } }`, combined with `concat`, so an unmatched
 * path rejects and the next is tried.
 *
 * Endpoints only — nothing here generates an OpenAPI document, which is why a
 * service can depend on this module without the generator. See
 * `pelican-pekko-docs`.
 */
fun Api.toRoute(system: ClassicActorSystemProvider, handlers: Handlers = Handlers.default): Route {
    require(admission == null) { "This interpreter does not support operation admission." }
    // Once per endpoint, captured by the routes: KType -> JavaType reflection
    // is not free, and a broken codec becomes a startup failure.
    val codecs = endpoints.associateWith { it.endpoint.resolveCodecs(this.codecs) }
    val plans = endpoints.associateWith { it.endpoint.plan() }

    // Worked out once from the descriptions, as with the codecs above.
    val cors = corsPolicy()

    // Folded around each handler once rather than per request, likewise. A synchronous handler moves off the
    // dispatcher with its filters, so a filter that blocks does not hold a dispatcher thread either.
    val bound = endpoints.associateWith { endpoint -> handlerFor(endpoint).on(handlers, endpoint) }

    require(endpoints.isNotEmpty()) { "This API has no endpoints." }

    // One route per *method* rather than one per endpoint. Inside each, the
    // index finds the endpoint by walking the path's segments, so the work does
    // not grow with the number of endpoints — two hundred of them used to cost
    // about 150µs a request, which is what an ordered scan costs and is the
    // same registered by hand.
    //
    // Per method rather than one route overall because Pekko's method rejection
    // is what produces a 405 for a verb no endpoint declares anywhere. A single
    // route would answer every one of those as a 404 instead.
    val index = endpoints.routeIndex()
    val routes = endpoints
        .map { it.endpoint.method }
        .distinct()
        .map { method -> methodRoute(method, this, index, plans, codecs, bound, cors, system) } +
        listOfNotNull(cors?.let { preflightRoute(it, this) }) +
        // Last, so an endpoint at a page's path answers as the endpoint (spec 0059).
        listOfNotNull(pages?.let { pagesRoute(it, cors) })

    val route = routes.reduce { left, right -> Directives.concat(left, right) }
    val policy = responseHeaders ?: return route
    val methods = endpoints.map { it.endpoint.method }
    return route.answeredUnder(policy, isDocs = false) { req ->
        val method = methods.firstOrNull { it.name == req.method().name() }
        method?.let { index.routeFor(it, req.uri.getPathString()) }?.endpoint
    }
}

/** The route as it was built before spec 0058, kept so a caller compiled against RC2 still links. */
@Deprecated("Binary compatibility only: call toRoute(system, handlers).", level = DeprecationLevel.HIDDEN)
fun Api.toRoute(system: ClassicActorSystemProvider): Route = toRoute(system, Handlers.default)

/**
 * [handler] on [threads]' executor, when [endpoint] was bound synchronously, not [ServerEndpoint.nonBlocking], and
 * there is one to move to.
 */
private fun ((Params) -> CompletionStage<Any?>).on(
    threads: Handlers,
    endpoint: ServerEndpoint,
): (Params) -> CompletionStage<Any?> {
    val executor = threads.executor
    return if (executor == null || endpoint.invoke !is Synchronous || endpoint.isNonBlocking) {
        this
    } else {
        { params -> CompletableFuture.supplyAsync({ this(params) }, executor).thenCompose { it } }
    }
}

/**
 * Everything this API answers under one method, dispatched by the index.
 *
 * A path the index does not know rejects, so a Pelican route concatenated with
 * hand-written ones passes on what it does not describe — the property
 * `ConcatenatedRoutesTest` and `MountedAlongsideTest` are about.
 */
private fun methodRoute(
    method: Method,
    api: Api,
    index: RouteIndex,
    plans: Map<ServerEndpoint, EndpointPlan>,
    codecs: Map<ServerEndpoint, EndpointCodecs>,
    handlers: Map<ServerEndpoint, (Params) -> CompletionStage<Any?>>,
    cors: CorsPolicy?,
    system: ClassicActorSystemProvider,
): Route = Directives.method(method.toPekko()) {
    Directives.extractRequest { req ->
        // Pekko's parsed path rendered back out, so it still carries every escape that changes what a segment is:
        // `%2F` stays inside its segment until the index splits and then decodes, once.
        val path = req.uri.getPathString()
        val matched = try {
            index.find(method, path)
        } catch (t: Throwable) {
            // A literal segment that would not decode is still the 400 of the route it was meant for.
            return@extractRequest Directives.complete(errorResponse(t, api, index.routeFor(method, path)?.endpoint))
        } ?: return@extractRequest Directives.reject()

        val values = plans.getValue(matched).slots()
        try {
            index.decodeCaptures(matched, path, values)
        } catch (t: Throwable) {
            // A capture that would not decode is its own route's 400.
            return@extractRequest Directives.complete(errorResponse(t, api, matched.endpoint))
        }
        val view = PekkoView(req)
        val answered = invoke(
            matched,
            api,
            plans.getValue(matched),
            codecs.getValue(matched),
            handlers.getValue(matched),
            view,
            values,
            system,
        )
        val withCors: (HttpResponse) -> HttpResponse =
            if (cors == null) { res -> res }
            else { res -> res.withCorsHeaders(cors.actualResponseHeaders(view.header(CorsHeaders.ORIGIN))) }
        // An answer already made is completed as a value, not handed to Pekko as a future to wait on.
        val now = answered.succeeded()
        if (now != null) Directives.complete(withCors(now.resultNow()))
        else Directives.completeWithFuture(answered.thenApply(withCors))
    }
}

/**
 * Answers a browser's preflight `OPTIONS`. Last in the `concat`, so an endpoint
 * declaring `OPTIONS` itself is tried first and a path nobody described is
 * still a 404.
 */
private fun preflightRoute(cors: CorsPolicy, api: Api): Route =
    Directives.method(HttpMethods.OPTIONS) {
        Directives.extractRequest { req ->
            val decision = cors.preflight(
                origin = req.getHeader(CorsHeaders.ORIGIN).orElse(null)?.value(),
                requestMethod = req.getHeader(CorsHeaders.REQUEST_METHOD).orElse(null)?.value(),
                path = req.uri.getPathString(),
            )
            when (decision) {
                is CorsPreflight.NotPreflight -> Directives.reject()

                is CorsPreflight.Refused ->
                    Directives.complete(errorResponse(ApiException(403, "Forbidden", decision.reason), api))

                is CorsPreflight.Allowed -> Directives.complete(
                    HttpResponse.create()
                        .withStatus(StatusCodes.NO_CONTENT)
                        .withCorsHeaders(decision.headers),
                )
            }
        }
    }

/** Pekko models headers as values too; these are all plain ones. */
private fun HttpResponse.withCorsHeaders(headers: List<Pair<String, String>>): HttpResponse =
    addHeaders(headers.map { (name, value) -> RawHeader.create(name, value) })

/**
 * The codecs one endpoint needs, resolved ahead of any request. Both null for
 * an endpoint that moves no JSON, which is why such an API needs none.
 */
internal class EndpointCodecs(
    val body: RequestBodyCodecs?,
    val payload: BodyCodec<Any?>?,
    /**
     * One per declared response, keyed by identity: two can carry the same
     * payload type under different statuses.
     */
    val alternatives: Map<Any, BodyCodec<Any?>> = emptyMap(),
) {
    /** The codec for whichever response a handler named; [payload] is the first success's. */
    fun payloadFor(out: Output<*>): BodyCodec<Any?>? = alternatives[out] ?: payload
}

private fun Endpoint<*, *>.resolveCodecs(codecs: Codecs): EndpointCodecs = EndpointCodecs(
    body = codecs.requestBodyCodec(this),
    payload = output.payloadType?.let { codecs.codec(it) },
    alternatives = codecs.responseCodecs(output),
)

/**
 * More specific paths first, so `/orders/watch` wins over `/orders/{orderId}`
 * whichever was declared first. Ties keep declaration order.
 */
internal fun Api.orderedEndpoints(): List<ServerEndpoint> =
    endpoints.withIndex().sortedWith(
        compareByDescending<IndexedValue<ServerEndpoint>> { (_, se) ->
            se.endpoint.pathSpec.segments.count { it is PathSegment.Literal }
        }.thenBy { it.index },
    ).map { it.value }

/**
 * Refuses a request whose `Accept` takes nothing this endpoint sends. The types
 * come from [Output.produces] and the decision from [acceptable], so the three
 * backends answer alike. An endpoint with no body never reads the header.
 */
private fun negotiate(ep: Endpoint<*, *>, accept: List<String>) {
    val produced = ep.output.produces
    if (produced.isEmpty()) return
    if (accept.isEmpty()) return
    if (!acceptable(accept, produced)) throw NotAcceptable(produced)
}

/**
 * The `Last-Event-ID` of a reconnect, read only where the endpoint answers with
 * a stream that could be resumed — which is core's answer, not this file's.
 */
private fun HttpRequest.resumePoint(ep: Endpoint<*, *>): String? =
    if (ep.resumable) getHeader(SseOutput.LAST_EVENT_ID).orElse(null)?.value() else null

/**
 * The body, read the way its declaration says to: not at all, as a stream
 * handed over unconsumed, as a multipart envelope parsed off the blocking-IO
 * pool, or as a strict payload for a codec.
 */
private fun readBody(
    ep: Endpoint<*, *>,
    api: Api,
    codecs: EndpointCodecs,
    req: HttpRequest,
    values: Slots,
    system: ClassicActorSystemProvider,
): CompletionStage<Unit> {
    return when (val body = ep.bodyInput) {
        null -> CompletableFuture.completedFuture(Unit)

        is RawBody -> {
            // Handed over unconsumed: no buffering, full back-pressure.
            values[body] = PekkoByteStream(req.entity().dataBytes as Source<ByteString, Any>)
            CompletableFuture.completedFuture(Unit)
        }

        // Framed by core as the bytes arrive, so nothing is held but the frame
        // being read. `statefulMap` rather than `statefulMapConcat` because the
        // reader has a tail: a producer need not end its last document with a
        // newline, and only the completion hook can hand that frame over.
        is NdjsonBody<*> -> {
            val frames = api.ndjsonFrames(ep, codecs.body, req.entity().contentType.toString())
            values[body] = PekkoFrames<Any?>(
                req.entity()
                    .dataBytes
                    .statefulMap(
                        { frames },
                        // Pekko's own `Pair`, spelled out: imported, it would
                        // shadow `kotlin.Pair`, which this file's header
                        // handling is written in.
                        { reader, chunk ->
                            org.apache.pekko.japi.Pair.create(reader, reader.push(chunk.toArray()))
                        },
                        { reader -> Optional.of(reader.end()) },
                    )
                    .mapConcat { it }
                    .mapMaterializedValue { NotUsed.getInstance() },
                system,
            )
            CompletableFuture.completedFuture(Unit)
        }

        // Core parses the envelope, so this turns a Pekko source into the
        // `InputStream` it reads. That read blocks, and it must not block on
        // the dispatcher running the source feeding it — a blocking consumer
        // sharing a pool with its own producer deadlocks rather than merely
        // running slowly. Hence the dedicated blocking-IO pool.
        //
        // Exempt from the size limit as a raw body is: the streamed part is
        // never held whole, and the limit bounds the parts that are.
        is MultipartBody -> {
            val stream = req.entity()
                .dataBytes
                .runWith(StreamConverters.asInputStream(), system)
            CompletableFuture.supplyAsync(
                {
                    body.decode(
                        contentType = req.entity().contentType.toString(),
                        input = stream,
                        maxInMemoryBytes = api.maxBodyBytes,
                        into = values,
                    )
                },
                system.classicSystem().dispatchers().lookup(BLOCKING_IO_DISPATCHER),
            )
        }

        is JsonBody<*>, is FormBody<*>, is NegotiatedBody<*> ->
            readStrictBody(ep, api, codecs, req, body, values, system)
    }
}

/** A body a codec sees whole: read into memory, checked against the limit, decoded. */
private fun readStrictBody(
    ep: Endpoint<*, *>,
    api: Api,
    codecs: EndpointCodecs,
    req: HttpRequest,
    body: BodyInput<*>,
    values: Slots,
    system: ClassicActorSystemProvider,
): CompletionStage<Unit> {
    // Before anything is read, so a declared oversize costs no transfer.
    val declaredLength = req.entity().contentLengthOption
    if (declaredLength.isPresent && declaredLength.asLong > api.maxBodyBytes) {
        return CompletableFuture.failedFuture(PayloadTooLarge(api.maxBodyBytes))
    }

    // `toStrict(timeout, maxBytes, system)` materialises a limiting stage in
    // front of the entity, costing about six microseconds a request — an order
    // of magnitude more than everything else here put together. It is only
    // needed when nobody said how long the body is: a declared Content-Length
    // was checked above, and HTTP/1.1 reads exactly that many bytes.
    //
    // The stage gets the limit plus a small overrun, and the refusal below is
    // made on what arrived. Cutting the entity off mid-upload leaves unread
    // bytes on the connection, so Pekko closes the socket while the client is
    // still writing and the client sees a broken pipe instead of the 413.
    // Reading the last few kilobytes buys a request that ends in an answer; a
    // client that keeps pushing past the overrun is cut off as before.
    val reading =
        if (declaredLength.isPresent) {
            req.entity().toStrict(api.strictBodyTimeoutMillis, system)
        } else {
            req.entity()
                .toStrict(api.strictBodyTimeoutMillis, api.maxBodyBytes + DRAIN_OVERRUN_BYTES, system)
        }
    return reading
        .exceptionally { t ->
            // The backstop for a chunked request. Pekko signals it with an
            // EntityStreamException; core names the condition itself.
            throw when {
                isSizeLimit(t) -> PayloadTooLarge(api.maxBodyBytes)

                // `toStrict` gives up on a body that stops arriving, and the
                // caller is the one who can act on that. Ktor already answers
                // 408 here; this used to fall through as a 500, so the same
                // `strictBodyTimeoutMillis` meant two different answers.
                isReadTimeout(t) -> ApiException(
                    REQUEST_TIMEOUT,
                    "Timed out reading the request body",
                    "The body did not arrive within the ${api.strictBodyTimeoutMillis}ms this service " +
                        "waits for one. Send it faster, or raise strictBodyTimeoutMillis in api { }.",
                )

                else -> t
            }
        }
        .thenApply { strict ->
            // Against the limit itself, not the ceiling the read was given.
            // The overrun has been consumed, so the connection is whole.
            if (strict.data.size() > api.maxBodyBytes) throw PayloadTooLarge(api.maxBodyBytes)

            // Which codec, and what an undeclared media type means, are core's
            // answers — see `RequestBodyCodecs`. So is wrapping what it threw.
            values[body] = checkNotNull(codecs.body) { "No codec was resolved for the body of $ep" }
                // `toArrayUnsafe`: the entity is strict and already whole, so this
                // hands over the bytes rather than copying them into UTF-16 for a
                // parser that reads UTF-8. See spec 0046.
                .decode(req.entity().contentType.toString(), strict.data.toArrayUnsafe()) {
                    req.getHeader(it).orElse(null)?.value()
                }
            Unit
        }
}

private fun invoke(
    se: ServerEndpoint,
    api: Api,
    plan: EndpointPlan,
    codecs: EndpointCodecs,
    handler: (Params) -> CompletionStage<Any?>,
    view: PekkoView,
    values: Slots,
    system: ClassicActorSystemProvider,
): CompletionStage<HttpResponse> {
    val ep = se.endpoint
    val req = view.request

    // Built before decoding, so a filter or a failing decode can still put a header on the way out.
    val params = Params(values, req, ep, resumeFrom = req.resumePoint(ep), requestHeaders = view)
    val accept = view.headers(ACCEPT)

    // Who is calling, then what they will take, then the inputs: a 401 tells nothing else, and a caller who will not
    // read what this endpoint sends is refused before a bad query parameter can decide the answer.
    try {
        api.decodeCaller(ep, view, values)
        negotiate(ep, accept)
        plan.decodePlainInputs(view, values)
    } catch (t: Throwable) {
        return CompletableFuture.completedFuture(errorResponse(t, api, ep).withHeaders(params))
    }

    val read = readBody(ep, api, codecs, req, values, system)
    val handled = if (read.succeeded() == null) {
        read.thenCompose { handler(params) }
    } else {
        try {
            handler(params)
        } catch (t: Throwable) {
            CompletableFuture.failedFuture(t)
        }
    }
    // A handler that has already answered, as every synchronous one has, is answered without a stage per step.
    val now = handled.succeeded()
    if (now != null) {
        val res = try {
            buildResponse(ep.output, now.resultNow(), codecs, accept)
        } catch (t: Throwable) {
            errorResponse(t, api, ep)
        }
        return CompletableFuture.completedFuture(res.withHeaders(params))
    }
    return handled
        .thenApply { result -> buildResponse(ep.output, result, codecs, accept) }
        .exceptionally { t -> errorResponse(t, api, ep) }
        .thenApply { res -> res.withHeaders(params) }
}

/** Pekko's request read in place: what an authenticator, `Params.requestHeader` and the plain inputs read. */
internal class PekkoView(val request: HttpRequest) : RequestView {
    override val method: String get() = request.method().name()
    override val rawPath: String get() = request.uri.getPathString()
    override val rawQuery: String? get() = request.uri.rawQueryString().orElse(null)

    override fun header(name: String): String? = request.getHeader(name).orElse(null)?.value()

    override fun headers(name: String): List<String> =
        request.getHeaders().filter { it.name().equals(name, ignoreCase = true) }.map { it.value() }
}

private const val ACCEPT = "Accept"

/** What the handler asked for, on whatever response came back. */
private fun HttpResponse.withHeaders(params: Params): HttpResponse {
    val extra = params.responseHeaders()
    return if (extra.isEmpty()) this
    else addHeaders(extra.map { (name, value) -> RawHeader.create(name, value) })
}

/**
 * Pekko reports truncation as an `EntityStreamException`, the same type it uses
 * for other entity problems, so the message is matched too. Anything else keeps
 * its 500, and the Content-Length check catches the ordinary case first.
 */

/**
 * What `toStrict` throws when the body stops arriving, wrapped or not.
 *
 * `internal` so it can be asserted without a clock. The end-to-end path — a
 * real body that stalls, a real 408 on the socket — is a race on a shared CI
 * runner, and a test that fails on a slow machine is worse than the one it
 * replaced.
 */
internal fun isReadTimeout(t: Throwable): Boolean =
    generateSequence(t) { it.cause }.any { it is java.util.concurrent.TimeoutException }

/** RFC 9110's status for a caller who did not finish sending in time. */
private const val REQUEST_TIMEOUT = 408

private fun isSizeLimit(t: Throwable): Boolean =
    generateSequence(t) { it.cause }.any {
        it is org.apache.pekko.stream.StreamLimitReachedException ||
            it::class.java.name.endsWith("EntityStreamSizeException") ||
            (
                it::class.java.name.endsWith("EntityStreamException") &&
                    it.message?.contains("longer than the maximum") == true
                )
    }
