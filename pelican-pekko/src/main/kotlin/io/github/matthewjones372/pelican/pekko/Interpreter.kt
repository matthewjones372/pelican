package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.*
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.http.javadsl.model.*
import org.apache.pekko.http.javadsl.model.headers.RawHeader
import org.apache.pekko.http.javadsl.server.Directives
import org.apache.pekko.http.javadsl.server.Route
import org.apache.pekko.stream.javadsl.Source
import org.apache.pekko.stream.javadsl.StreamConverters
import org.apache.pekko.util.ByteString
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Pekko's own pool for work that blocks — sixteen plain threads, separate from
 * the fork-join pool that runs actors and stream stages. Anything that parks a
 * thread waiting on IO belongs here rather than on the default dispatcher.
 */
private const val BLOCKING_IO_DISPATCHER = "pekko.actor.default-blocking-io-dispatcher"

/**
 * How far past [Api.maxBodyBytes] a body with no declared length is still read
 * before the connection is cut, so that the 413 it earns can be delivered on a
 * connection that is still whole. Sixty-four kilobytes: a body that overshoots
 * by less than that is the ordinary case — a client that guessed wrong, not one
 * that is attacking — and reading it out is cheap next to any limit worth
 * setting. See the read itself for what this buys.
 */
private const val DRAIN_OVERRUN_BYTES: Long = 64L * 1024L

/**
 * Interprets an [Api] as a Pekko HTTP route.
 *
 * Each endpoint becomes `method { extractRequest { ... completeWithFuture } }`
 * and the set is combined with `concat`, so an endpoint whose path does not
 * match simply rejects and the next is tried — Pekko's own routing mechanics,
 * driven by the descriptions instead of hand-written directive nesting.
 *
 * Endpoints only. Nothing here generates or serves an OpenAPI document, which
 * is why a service can depend on this module without the doc generator being
 * compiled in at all — see `pelican-pekko-docs` for `startWithDocs`.
 */
fun Api.toRoute(system: ClassicActorSystemProvider): Route {
    // Codecs are resolved here, once per endpoint, and captured by the routes.
    // KType -> JavaType reflection is not free, and doing it per request would
    // put it on the hot path for no benefit. It also means a missing or broken
    // codec is a startup failure rather than a surprise on the first request.
    val codecs = endpoints.associateWith { it.endpoint.resolveCodecs(this.codecs) }

    // Worked out once, from the descriptions, and captured by the routes — the
    // same shape as the codecs above and for the same reason.
    val cors = corsPolicy()

    // Filters are folded around each handler here, once, rather than per
    // request — the same reasoning as the codecs above.
    val handlers = endpoints.associateWith { handlerFor(it) }

    val routes = orderedEndpoints().map {
        routeFor(it, this, codecs.getValue(it), handlers.getValue(it), cors, system)
    } + listOfNotNull(cors?.let(::preflightRoute))
    require(routes.isNotEmpty()) { "This API has no endpoints." }
    return routes.reduce { left, right -> Directives.concat(left, right) }
}

/**
 * Answers the `OPTIONS` a browser sends before anything interesting.
 *
 * Last in the `concat`, so an endpoint that declares `OPTIONS` for itself is
 * tried first and this never sees the request. Everything else rejects, which
 * leaves Pekko to answer as it would have — a path nobody described is still a
 * 404.
 */
private fun preflightRoute(cors: CorsPolicy): Route =
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
                    Directives.complete(errorResponse(ApiException(403, "Forbidden", decision.reason), null))

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
 * The codecs one endpoint needs, resolved ahead of any request.
 *
 * Both are null for an endpoint that moves no JSON — a byte-stream echo, a
 * 204 — which is why such an API needs no codec configured at all.
 */
internal class EndpointCodecs(
    val body: RequestBodyCodecs?,
    val payload: BodyCodec<Any?>?,
    /**
     * One per declared response — success or failure — keyed by identity. Two
     * responses can carry the same payload type under different statuses, so
     * the declaration is the key rather than the type.
     */
    val alternatives: Map<Any, BodyCodec<Any?>> = emptyMap(),
) {
    /** The codec for whichever response a handler named; [payload] is the first success's. */
    fun payloadFor(out: Output<*>): BodyCodec<Any?>? = alternatives[out] ?: payload
}

private fun Endpoint<*, *>.resolveCodecs(codecs: Codecs): EndpointCodecs = EndpointCodecs(
    body = codecs.requestBodyCodec(bodyInput),
    payload = output.payloadType?.let { codecs.codec(it) },
    alternatives = (output as? FallibleOutput<*, *>)?.let { declared ->
        (
            declared.successes.mapNotNull { s -> s.payloadType?.let { s as Any to codecs.codec<Any?>(it) } } +
                declared.failures.map { f -> f as Any to codecs.codec<Any?>(f.type) }
            ).associateTo(java.util.IdentityHashMap<Any, BodyCodec<Any?>>()) { it }
    }.orEmpty(),
)

/**
 * More specific paths are matched first, so `/orders/watch` wins over
 * `/orders/{orderId}` no matter which was declared first. Ties keep
 * declaration order, which stays predictable.
 */
internal fun Api.orderedEndpoints(): List<ServerEndpoint> =
    endpoints.withIndex().sortedWith(
        compareByDescending<IndexedValue<ServerEndpoint>> { (_, se) ->
            se.endpoint.pathSpec.segments.count { it is PathSegment.Literal }
        }.thenBy { it.index },
    ).map { it.value }

private fun routeFor(
    se: ServerEndpoint,
    api: Api,
    codecs: EndpointCodecs,
    handler: (Params) -> CompletionStage<Any?>,
    cors: CorsPolicy?,
    system: ClassicActorSystemProvider,
): Route = Directives.method(se.endpoint.method.toPekko()) {
    Directives.extractRequest { req ->
        val captures = matchPath(se.endpoint.pathSpec, req)
        if (captures == null) {
            Directives.reject()
        } else {
            val answered = invoke(se, api, codecs, handler, req, captures, system)
            Directives.completeWithFuture(
                // The headers go on whatever came back — a handler's value, a
                // decode failure, an exception. A browser needs them on the
                // error as much as on the success: without them the script
                // sees a network error instead of the 400 that explains
                // itself.
                //
                // Left alone when CORS is off: `thenApply` on an answer that
                // needs nothing done to it still allocates a stage and a
                // closure, on every request, for every API that never
                // configured CORS.
                if (cors == null) {
                    answered
                } else {
                    answered.thenApply { res -> res.withCorsHeaders(cors.actualResponseHeaders(originOf(req))) }
                },
            )
        }
    }
}

private fun originOf(req: HttpRequest): String? =
    req.getHeader(CorsHeaders.ORIGIN).orElse(null)?.value()

/** Returns the captured segments, or null when this request is for another endpoint. */
private fun matchPath(spec: PathSpec, req: HttpRequest): Map<PathParam<*>, String>? {
    // Walked in one pass over the path string rather than split into a list,
    // filtered into a second and decoded into a third. This runs for every
    // endpoint a request is offered to until one matches, so what it allocates
    // is paid per candidate route rather than per request — three lists and a
    // map each time, for a comparison that mostly fails.
    val path = req.uri.getPathString()
    val segments = spec.segments

    // Allocated once, and only when the route has something to capture.
    val captured =
        if (spec.captures.isEmpty()) null else LinkedHashMap<PathParam<*>, String>(spec.captures.size)

    var index = 0
    var at = 0

    while (at < path.length) {
        if (path[at] == '/') { at++; continue }
        val end = segmentEnd(path, at)
        if (index == segments.size) return null

        when (val segment = segments[index]) {
            // Compared in place. A literal is the common case and the one that
            // decides whether this route is even a candidate.
            is PathSegment.Literal -> if (!segment.matchesAt(path, at, end)) return null

            is PathSegment.Capture -> captured?.put(segment.param, decodeSegment(path, at, end))
        }

        index++
        at = end
    }

    return if (index != segments.size) null else captured.orEmpty()
}

/** Where this path segment ends: the next slash, or the end of the path. */
private fun segmentEnd(path: String, from: Int): Int {
    val next = path.indexOf('/', from)
    return if (next < 0) path.length else next
}

/** A literal segment, compared against the path in place rather than copied out of it. */
private fun PathSegment.Literal.matchesAt(path: String, from: Int, to: Int): Boolean =
    value.length == to - from && path.regionMatches(from, value, 0, to - from)

/** Decoding allocates, and most path segments have nothing in them to decode. */
private fun decodeSegment(path: String, from: Int, to: Int): String {
    val raw = path.substring(from, to)
    val encoded = raw.any { it == '%' || it == '+' }
    return if (encoded) URLDecoder.decode(raw, StandardCharsets.UTF_8) else raw
}

/** Every field line under this name, in the order they arrived. */
private fun HttpRequest.headerValues(name: String): List<String> =
    getHeaders().filter { it.name().equals(name, ignoreCase = true) }.map { it.value() }

@Suppress("UNCHECKED_CAST")
/**
 * Everything decodable before the body arrives, written into the request's own
 * value bag.
 *
 * The three rules are the same one written three times over — present, decode
 * it; absent and required, refuse; absent and optional, take the declared
 * default — and they are spelled out per input kind because what "present"
 * means differs: a query parameter and a header are Pekko `Optional`s, a
 * cookie is a lookup in a map core parsed, and a parameter declared as a list
 * is present when at least one occurrence of it carried something.
 */
private fun decodePlainInputs(
    ep: Endpoint<*, *>,
    req: HttpRequest,
    captures: Map<PathParam<*>, String>,
    into: MutableMap<ParamKey<*>, Any?>,
) = with(into) {
    val query = req.uri.query()

    captures.forEach { (param, raw) -> put(param, param.codec.decode(param.name, raw)) }

    ep.queries.forEach { q ->
        val style = q.listStyle
        if (style != null) {
            // Pekko walks the query forwards and prepends, so `getAll` hands
            // back the occurrences last-first. A list is ordered, and the
            // order the caller wrote is the one it means.
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
        // `getHeader` returns the first field line; a list is declared as
        // comma-separated and RFC 9110 says two lines mean the one joined
        // field, so it is the only case that has to read them all.
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
 * Cookies, parsed by core from the header rather than by Pekko's own `Cookie`
 * model — so a cookie decodes to the same value on all three backends. See
 * `Cookies`. Skipped when nothing declared one: reading and parsing the header
 * costs the same whether or not anybody asked for a cookie.
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
 *
 * Decoded values go into [values], and the stage says when they are there. A
 * refusal — oversize, undecodable — is the stage failing rather than a
 * response built here: the caller turns any failure into the same response
 * through core's `renderError`, so there is one place that decides what a
 * failure looks like.
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

        // The envelope is parsed by core, so this backend's job is to turn a
        // Pekko source into the `InputStream` that parser reads. Reading it
        // blocks, and it must not block on the dispatcher that runs the source
        // feeding it: the default dispatcher runs the stream's stages too, so
        // enough concurrent uploads would park every thread inside `read()`
        // with none left to produce the bytes those reads are waiting for.
        // A blocking consumer sharing a pool with its own producer deadlocks
        // rather than merely running slowly, so the parse goes to the
        // dedicated blocking-IO pool and the two never contend.
        //
        // Exempt from the size limit for the same reason a raw body is: the
        // streamed part is never held whole. What the parts that *are* held
        // may cost is bounded, and that is what the limit is passed in for.
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

/**
 * A body a codec has to see whole: read into memory, checked against the
 * limit, and decoded. Its own function because the reading is where the size
 * ceiling is enforced, and that is more than a line of reasoning.
 */
private fun readStrictBody(
    ep: Endpoint<*, *>,
    api: Api,
    codecs: EndpointCodecs,
    req: HttpRequest,
    body: BodyInput<*>,
    values: MutableMap<ParamKey<*>, Any?>,
    system: ClassicActorSystemProvider,
): CompletionStage<Unit> {
    // Checked before anything is read, so a declared oversize is refused
    // without pulling the body across at all. The three backends all do
    // this, and all answer 413.
    val declaredLength = req.entity().contentLengthOption
    if (declaredLength.isPresent && declaredLength.asLong > api.maxBodyBytes) {
        return CompletableFuture.failedStage(PayloadTooLarge(api.maxBodyBytes))
    }

    // Two ways to read it, and which one matters more than it looks.
    //
    // `toStrict(timeout, maxBytes, system)` enforces a ceiling by
    // materialising a limiting stage in front of the entity, and that costs
    // about six microseconds a request — an order of magnitude more than
    // everything else this interpreter does put together.
    //
    // It is only needed when nobody has said how long the body is. A
    // declared Content-Length was checked above, and HTTP/1.1 reads exactly
    // that many bytes off the connection, so the entity cannot arrive
    // larger than the number already refused. Chunked requests declare
    // nothing, and those still get the limiting stage.
    //
    // The ceiling handed to the stage is the limit plus a small overrun,
    // and the refusal is made below on what actually arrived. The overrun
    // is what makes the 413 reach the client at all. Cutting the entity off
    // mid-upload leaves unread bytes on the connection, so Pekko HTTP
    // answers with `Connection: close` and shuts the socket while the
    // client is still writing — which the client sees as a broken pipe
    // rather than as the refusal that explains it. Reading the last few
    // kilobytes of a body that is only a little too big costs a bounded
    // amount of memory and buys a request that ends in an answer. A client
    // that keeps pushing past the overrun is cut off as before: the stage
    // fails, the `exceptionally` below turns that into the same 413, and
    // that one may well arrive as a dropped connection. Nothing can be
    // promised to a sender that will not stop.
    val reading =
        if (declaredLength.isPresent) {
            req.entity().toStrict(api.strictBodyTimeoutMillis, system)
        } else {
            req.entity()
                .toStrict(api.strictBodyTimeoutMillis, api.maxBodyBytes + DRAIN_OVERRUN_BYTES, system)
        }
    return reading
        .exceptionally { t ->
            // The backstop, for a chunked request that declared no length.
            // Pekko signals it with an EntityStreamException; core has its
            // own name for the condition so the three backends answer
            // alike.
            throw if (isSizeLimit(t)) PayloadTooLarge(api.maxBodyBytes) else t
        }
        .thenApply { strict ->
            // What arrived, against the limit itself rather than the
            // ceiling the read was given. Everything within the overrun has
            // been consumed by now, so the connection is whole and the
            // refusal can be written down it.
            if (strict.data.size() > api.maxBodyBytes) throw PayloadTooLarge(api.maxBodyBytes)

            // Which codec, and what a media type nobody declared means, are
            // core's answers — see `RequestBodyCodecs`. So is wrapping whatever
            // the codec threw, which is what keeps this file codec-agnostic.
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
    captures: Map<PathParam<*>, String>,
    system: ClassicActorSystemProvider,
): CompletionStage<HttpResponse> {
    val ep = se.endpoint
    val values = LinkedHashMap<ParamKey<*>, Any?>()

    // Built before decoding, so a filter or a failing decode can still put a
    // header on the way out.
    val params = Params(values, req, ep)

    // ---- plain inputs: path, query, headers, cookies ----------------------
    try {
        decodePlainInputs(ep, req, captures, values)
    } catch (t: Throwable) {
        return CompletableFuture.completedStage(errorResponse(t, api, ep).withHeaders(params))
    }

    val bodyReady = readBody(ep, api, codecs, req, values, system)

    return bodyReady
        .thenCompose { handler(params) }
        .thenApply { result -> buildResponse(ep.output, result, codecs) }
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
 * Pekko reports the truncation as an `EntityStreamException` — the same type it
 * uses for other entity problems — so the class alone is not enough to tell
 * them apart. Matching the class *and* the limit it names is narrow enough to
 * be safe: anything else keeps its own 500, and the Content-Length check above
 * catches the ordinary case before this is reached at all.
 */
private fun isSizeLimit(t: Throwable): Boolean =
    generateSequence(t) { it.cause }.any {
        it is org.apache.pekko.stream.StreamLimitReachedException ||
            it::class.java.name.endsWith("EntityStreamSizeException") ||
            (
                it::class.java.name.endsWith("EntityStreamException") &&
                    it.message?.contains("longer than the maximum") == true
                )
    }
