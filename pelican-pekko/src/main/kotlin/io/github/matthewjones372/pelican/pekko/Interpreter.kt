package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.*
import io.github.matthewjones372.pelican.spi.*
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.http.javadsl.model.*
import org.apache.pekko.http.javadsl.model.headers.RawHeader
import org.apache.pekko.http.javadsl.server.Directives
import org.apache.pekko.http.javadsl.server.Route
import org.apache.pekko.stream.javadsl.Source
import org.apache.pekko.stream.javadsl.StreamConverters
import org.apache.pekko.util.ByteString
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
fun Api.toRoute(system: ClassicActorSystemProvider): Route {
    // Once per endpoint, captured by the routes: KType -> JavaType reflection
    // is not free, and a broken codec becomes a startup failure.
    val codecs = endpoints.associateWith { it.endpoint.resolveCodecs(this.codecs) }

    // Worked out once from the descriptions, as with the codecs above.
    val cors = corsPolicy()

    // Folded around each handler once rather than per request, likewise.
    val handlers = endpoints.associateWith { handlerFor(it) }

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
        .map { method -> methodRoute(method, this, index, codecs, handlers, cors, system) } +
        listOfNotNull(cors?.let { preflightRoute(it, this) })

    return routes.reduce { left, right -> Directives.concat(left, right) }
}

/**
 * Everything this API answers under one method, dispatched by the index.
 *
 * A path the index does not know rejects, so a Pelican route concatenated with
 * hand-written ones passes on what it does not describe — the property
 * `ConcatenatedRoutesTest` and `MountedAlongsideTest` are about.
 */
@Suppress("LongParameterList") // The route's whole world, resolved once and captured.
private fun methodRoute(
    method: Method,
    api: Api,
    index: RouteIndex,
    codecs: Map<ServerEndpoint, EndpointCodecs>,
    handlers: Map<ServerEndpoint, (Params) -> CompletionStage<Any?>>,
    cors: CorsPolicy?,
    system: ClassicActorSystemProvider,
): Route = Directives.method(method.toPekko()) {
    Directives.extractRequest { req ->
        val values = LinkedHashMap<ParamKey<*>, Any?>()

        // A path that decodes into the wrong type is a 400 naming the
        // parameter, which is what the scan did when it decoded a capture.
        //
        // `getPathString` renders Pekko's parsed path back out, so what the
        // index gets still carries every escape that changes what a segment is
        // — `%2F` stays `%2F` rather than becoming a separator. The index does
        // the decoding, once, after splitting.
        val matched = try {
            index.match(method, req.uri.getPathString(), values)
        } catch (t: Throwable) {
            return@extractRequest Directives.complete(errorResponse(t, api))
        }

        if (matched == null) {
            Directives.reject()
        } else {
            val answered =
                invoke(matched, api, codecs.getValue(matched), handlers.getValue(matched), req, values, system)
            Directives.completeWithFuture(
                if (cors == null) answered
                else answered.thenApply { res -> res.withCorsHeaders(cors.actualResponseHeaders(originOf(req))) },
            )
        }
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
    body = codecs.requestBodyCodec(bodyInput),
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
private fun negotiate(ep: Endpoint<*, *>, req: HttpRequest) {
    val produced = ep.output.produces
    if (produced.isEmpty()) return
    val accept = req.acceptLines()
    if (accept.isEmpty()) return
    if (!acceptable(accept, produced)) throw NotAcceptable(produced)
}

/** Every `Accept` field line: RFC 9110 reads two lines as one field. */
private fun HttpRequest.acceptLines(): List<String> = headerValues("Accept")

/**
 * The `Last-Event-ID` of a reconnect, read only where the endpoint answers with
 * a stream that could be resumed — which is core's answer, not this file's.
 */
private fun HttpRequest.resumePoint(ep: Endpoint<*, *>): String? =
    if (ep.resumable) getHeader(SseOutput.LAST_EVENT_ID).orElse(null)?.value() else null

private fun originOf(req: HttpRequest): String? =
    req.getHeader(CorsHeaders.ORIGIN).orElse(null)?.value()

/** Every field line under this name, in the order they arrived. */
private fun HttpRequest.headerValues(name: String): List<String> =
    getHeaders().filter { it.name().equals(name, ignoreCase = true) }.map { it.value() }

@Suppress("UNCHECKED_CAST")
/**
 * Everything decodable before the body arrives.
 */
private fun decodePlainInputs(
    ep: Endpoint<*, *>,
    req: HttpRequest,
    into: MutableMap<ParamKey<*>, Any?>,
) = with(into) {
    // Path captures are already here: the index decoded them as it matched.
    val query = req.uri.query()

    ep.queries.forEach { q ->
        val style = q.listStyle
        if (style != null) {
            // Pekko prepends as it walks, so `getAll` is last-first.
            put(q, q.decodeList(query.getAll(q.name).reversed()))
            return@forEach
        }
        val raw = query.get(q.name)
        put(
            q,
            when {
                raw.isPresent -> q.codec.decode(q.name, raw.get())
                q.required -> throw ApiException(400, "Missing required query parameter '${q.name}'")
                else -> q.default
            },
        )
    }

    ep.headerParams.forEach { h ->
        // `getHeader` returns the first line only, and RFC 9110 says two lines
        // mean one joined field — so a list is the case that reads them all.
        if (h.listStyle != null) {
            put(h, h.decodeList(req.headerValues(h.name)))
            return@forEach
        }
        val raw = req.getHeader(h.name)
        put(
            h,
            when {
                raw.isPresent -> h.codec.decode(h.name, raw.get().value())
                h.required -> throw ApiException(400, "Missing required header '${h.name}'")
                else -> h.default
            },
        )
    }

    decodeCookies(ep, req, into)
}

/**
 * Parsed by core rather than by Pekko's `Cookie` model, so a cookie decodes the
 * same on all three backends. Skipped when nothing declared one.
 */
private fun decodeCookies(ep: Endpoint<*, *>, req: HttpRequest, into: MutableMap<ParamKey<*>, Any?>) {
    if (ep.cookieParams.isEmpty()) return

    val cookies = Cookies.parseAll(req.headerValues("Cookie"))
    ep.cookieParams.forEach { c ->
        if (c.listStyle != null) {
            into[c] = c.decodeList(cookies[c.name].orEmpty())
            return@forEach
        }
        val raw = cookies[c.name]?.first()
        into[c] = when {
            raw != null -> c.codec.decode(c.name, raw)
            c.required -> throw ApiException(400, "Missing required cookie '${c.name}'")
            else -> c.default
        }
    }
}

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
    values: MutableMap<ParamKey<*>, Any?>,
    system: ClassicActorSystemProvider,
): CompletionStage<Unit> {
    return when (val body = ep.bodyInput) {
        null -> CompletableFuture.completedStage(Unit)

        is RawBody -> {
            // Handed over unconsumed: no buffering, full back-pressure.
            values[body] = PekkoByteStream(req.entity().dataBytes as Source<ByteString, Any>)
            CompletableFuture.completedStage(Unit)
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
    values: MutableMap<ParamKey<*>, Any?>,
    system: ClassicActorSystemProvider,
): CompletionStage<Unit> {
    // Before anything is read, so a declared oversize costs no transfer.
    val declaredLength = req.entity().contentLengthOption
    if (declaredLength.isPresent && declaredLength.asLong > api.maxBodyBytes) {
        return CompletableFuture.failedStage(PayloadTooLarge(api.maxBodyBytes))
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
                .decode(req.entity().contentType.toString(), strict.data.utf8String())
            Unit
        }
}

private fun invoke(
    se: ServerEndpoint,
    api: Api,
    codecs: EndpointCodecs,
    handler: (Params) -> CompletionStage<Any?>,
    req: HttpRequest,
    values: MutableMap<ParamKey<*>, Any?>,
    system: ClassicActorSystemProvider,
): CompletionStage<HttpResponse> {
    val ep = se.endpoint

    // Built before decoding, so a filter or a failing decode can still put a
    // header on the way out.
    val params = Params(values, req, ep, resumeFrom = req.resumePoint(ep))

    // ---- what the caller will take ----------------------------------------
    //
    // Before the inputs, and well before the handler: a caller who will not
    // read what this endpoint sends is refused without the endpoint doing the
    // work, and without a bad query parameter deciding the answer first.
    try {
        negotiate(ep, req)
    } catch (t: Throwable) {
        return CompletableFuture.completedStage(errorResponse(t, api, ep).withHeaders(params))
    }

    // ---- plain inputs: path, query, headers, cookies ----------------------
    try {
        decodePlainInputs(ep, req, values)
    } catch (t: Throwable) {
        return CompletableFuture.completedStage(errorResponse(t, api, ep).withHeaders(params))
    }

    val bodyReady = readBody(ep, api, codecs, req, values, system)

    return bodyReady
        .thenCompose { handler(params) }
        .thenApply { result -> buildResponse(ep.output, result, codecs, req.acceptLines()) }
        .exceptionally { t -> errorResponse(t, api, ep) }
        .thenApply { res -> res.withHeaders(params) }
}

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
