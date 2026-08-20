package dev.pelican.pekko

import dev.pelican.*
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
    return Directives.concat(routes.first(), *routes.drop(1).toTypedArray())
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
    val body: BodyCodec<Any?>?,
    val payload: BodyCodec<Any?>?,
    /**
     * One per declared failure, keyed by identity — two failures can carry the
     * same payload type under different statuses, so the declaration is the
     * key rather than the type.
     */
    val failures: Map<ErrorOutput<*>, BodyCodec<Any?>> = emptyMap(),
)

private fun Endpoint<*, *>.resolveCodecs(codecs: Codecs): EndpointCodecs = EndpointCodecs(
    body = codecs.requestBodyCodec(bodyInput),
    payload = output.payloadType?.let { codecs.codec(it) },
    failures = (output as? FallibleOutput<*, *>)
        ?.failures
        ?.associateTo(java.util.IdentityHashMap()) { it to codecs.codec<Any?>(it.type) }
        ?: emptyMap(),
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
        if (captures == null) Directives.reject()
        else Directives.completeWithFuture(
            // The headers go on whatever came back — a handler's value, a
            // decode failure, an exception. A browser needs them on the error
            // as much as on the success: without them the script sees a network
            // error instead of the 400 that explains itself.
            invoke(se, api, codecs, handler, req, captures, system).thenApply { res ->
                if (cors == null) res
                else res.withCorsHeaders(cors.actualResponseHeaders(originOf(req)))
            },
        )
    }
}

private fun originOf(req: HttpRequest): String? =
    req.getHeader(CorsHeaders.ORIGIN).orElse(null)?.value()

/** Returns the captured segments, or null when this request is for another endpoint. */
private fun matchPath(spec: PathSpec, req: HttpRequest): Map<PathParam<*>, String>? {
    val parts = req.uri.getPathString()
        .split('/')
        .filter { it.isNotEmpty() }
        .map { URLDecoder.decode(it, StandardCharsets.UTF_8) }
    if (parts.size != spec.segments.size) return null
    val captured = LinkedHashMap<PathParam<*>, String>()
    spec.segments.forEachIndexed { i, segment ->
        when (segment) {
            is PathSegment.Literal -> if (segment.value != parts[i]) return null
            is PathSegment.Capture -> captured[segment.param] = parts[i]
        }
    }
    return captured
}

@Suppress("UNCHECKED_CAST")
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

    // ---- plain inputs: path, query, headers -------------------------------
    try {
        captures.forEach { (param, raw) -> values[param] = param.codec.decode(param.name, raw) }

        val query = req.uri.query()
        for (q in ep.queries) {
            val raw = query.get(q.name)
            values[q] = when {
                raw.isPresent -> q.codec.decode(q.name, raw.get())
                q.required -> throw ApiException(400, "Missing required query parameter '${q.name}'")
                else -> q.default
            }
        }

        for (h in ep.headerParams) {
            val raw = req.getHeader(h.name)
            values[h] = when {
                raw.isPresent -> h.codec.decode(h.name, raw.get().value())
                h.required -> throw ApiException(400, "Missing required header '${h.name}'")
                else -> h.default
            }
        }

        // Parsed by core, from the header, rather than by Pekko's own `Cookie`
        // model — so a cookie decodes to the same value on all three backends.
        // See `Cookies`.
        val cookies = Cookies.parse(
            req.getHeaders().filter { it.name().equals("Cookie", ignoreCase = true) }.map { it.value() },
        )
        for (c in ep.cookieParams) {
            val raw = cookies[c.name]
            values[c] = when {
                raw != null -> c.codec.decode(c.name, raw)
                c.required -> throw ApiException(400, "Missing required cookie '${c.name}'")
                else -> c.default
            }
        }
    } catch (t: Throwable) {
        return CompletableFuture.completedStage(errorResponse(t, api, ep).withHeaders(params))
    }

    // ---- body -------------------------------------------------------------
    val bodyReady: CompletionStage<Unit> = when (val body = ep.bodyInput) {
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
        // file part is never held whole. What the text parts may cost is
        // bounded, and that is what the limit is passed in for.
        is MultipartBody -> {
            val stream = req.entity()
                .dataBytes
                .runWith(StreamConverters.asInputStream(), system)
            CompletableFuture.supplyAsync(
                {
                    body.decode(
                        contentType = req.entity().contentType.toString(),
                        input = stream,
                        maxTextBytes = api.maxBodyBytes,
                        into = values,
                    )
                },
                system.classicSystem().dispatchers().lookup(BLOCKING_IO_DISPATCHER),
            )
        }

        is JsonBody<*>, is FormBody<*> -> {
            // Checked before anything is read, so a declared oversize is
            // refused without pulling the body across at all. The three
            // backends all do this, and all answer 413.
            val declaredLength = req.entity().contentLengthOption
            if (declaredLength.isPresent && declaredLength.asLong > api.maxBodyBytes) {
                return CompletableFuture.completedStage(
                    errorResponse(PayloadTooLarge(api.maxBodyBytes), api, ep).withHeaders(params),
                )
            }
            req.entity()
                .toStrict(api.strictBodyTimeoutMillis, api.maxBodyBytes, system)
                .exceptionally { t ->
                    // The backstop, for a chunked request that declared no length.
                    // Pekko signals it with an EntityStreamException; core has its
                    // own name for the condition so the three backends answer alike.
                    throw if (isSizeLimit(t)) PayloadTooLarge(api.maxBodyBytes) else t
                }
                .thenApply { strict ->
                    val text = strict.data.utf8String()
                    // Whatever the codec throws is its own library's exception, and
                    // nothing here should have to recognise it. Wrapping it in
                    // core's own failure is what keeps this file codec-agnostic.
                    values[body] = try {
                        checkNotNull(codecs.body) { "No codec was resolved for the body of $ep" }
                            .decodeFromString(text)
                    } catch (t: BodyDecodeFailure) {
                        throw t
                    } catch (t: Throwable) {
                        throw BodyDecodeFailure(t.message ?: "Could not decode the request body", t)
                    }
                    Unit
                }
        }
    }

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
