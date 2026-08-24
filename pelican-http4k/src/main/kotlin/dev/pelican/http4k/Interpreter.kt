package dev.pelican.http4k

import dev.pelican.Api
import dev.pelican.ApiException
import dev.pelican.BodyCodec
import dev.pelican.Codecs
import dev.pelican.Cookies
import dev.pelican.CorsHeaders
import dev.pelican.CorsPolicy
import dev.pelican.CorsPreflight
import dev.pelican.Endpoint
import dev.pelican.FallibleOutput
import dev.pelican.FormBody
import dev.pelican.JsonBody
import dev.pelican.MultipartBody
import dev.pelican.NegotiatedBody
import dev.pelican.Output
import dev.pelican.ParamKey
import dev.pelican.Params
import dev.pelican.PathSegment
import dev.pelican.PayloadTooLarge
import dev.pelican.RawBody
import dev.pelican.RequestBodyCodecs
import dev.pelican.ServerEndpoint
import dev.pelican.corsPolicy
import dev.pelican.declaredInputCount
import dev.pelican.decode
import dev.pelican.decodeList
import dev.pelican.handlerFor
import dev.pelican.requestBodyCodec
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes
import java.util.IdentityHashMap
import dev.pelican.Method as PelicanMethod
import org.http4k.core.Method as Http4kMethod

/**
 * Interprets an [Api] as an http4k [HttpHandler].
 *
 * Each endpoint becomes `template bind method to { request -> ... }` and the
 * set is combined with `routes(...)`, so path matching, template extraction and
 * the 404/405 rules are http4k's own — driven by the descriptions instead of
 * hand-written route declarations. An endpoint's [dev.pelican.PathSpec.template]
 * is already `/users/{userId}`, which is exactly http4k's template syntax, so
 * the two agree by construction rather than by translation.
 *
 * The result is a plain function from `Request` to `Response`. That is the
 * whole in-memory test story for this backend: call it. There is no equivalent
 * of `pelican-test`'s `InMemoryTransport` here because there is nothing left
 * for one to do.
 *
 * Endpoints only. Nothing here generates or serves an OpenAPI document, which
 * is why a service can depend on this module without the doc generator being
 * compiled in at all — see `pelican-http4k-docs` for `startWithDocs`.
 */
fun Api.toHttpHandler(): RoutingHttpHandler {
    // Codecs are resolved here, once per endpoint, and captured by the routes.
    // KType -> JavaType reflection is not free, and doing it per request would
    // put it on the hot path for no benefit. It also means a missing or broken
    // codec is a startup failure rather than a surprise on the first request.
    val resolved = endpoints.associateWith { it.endpoint.resolveCodecs(this.codecs) }

    // Worked out once, from the descriptions, and captured by the routes — the
    // same shape as the codecs above and for the same reason.
    val cors = corsPolicy()

    // Filters are folded around each handler here, once, rather than per
    // request — the same reasoning as the codecs above.
    val handlers = endpoints.associateWith { handlerFor(it) }

    val ordered = orderedEndpoints()
    val routes = ordered.map { routeFor(it, this, resolved.getValue(it), handlers.getValue(it), cors) } +
        preflightRoutes(ordered, cors)
    require(routes.isNotEmpty()) { "This API has no endpoints." }
    return routes(routes)
}

/**
 * One `OPTIONS` route per declared path, which is what a browser asks before it
 * sends anything interesting.
 *
 * A path that already declares an `OPTIONS` endpoint of its own keeps it: an
 * endpoint someone wrote down outranks one this module would have invented. The
 * routes come after the endpoints for the same reason, since http4k tries them
 * in order.
 */
private fun preflightRoutes(
    ordered: List<ServerEndpoint>,
    cors: CorsPolicy?,
): List<RoutingHttpHandler> {
    if (cors == null) return emptyList()

    val declaresOptions = ordered
        .filter { it.endpoint.method == PelicanMethod.OPTIONS }
        .map { it.endpoint.pathSpec.template }
        .toSet()

    return ordered
        .map { it.endpoint.pathSpec.template }
        .distinct()
        .filterNot { it in declaresOptions }
        .map { template ->
            template bind Http4kMethod.OPTIONS to { req: Request -> preflightResponse(cors, req) }
        }
}

private fun preflightResponse(cors: CorsPolicy, req: Request): Response =
    when (
        val decision = cors.preflight(
            origin = req.header(CorsHeaders.ORIGIN),
            requestMethod = req.header(CorsHeaders.REQUEST_METHOD),
            path = req.uri.path,
        )
    ) {
        // Not a preflight — a bare OPTIONS, or one aimed at no described path.
        // Nothing here describes an answer to that, which is what the router
        // would have said before this route existed.
        is CorsPreflight.NotPreflight ->
            errorResponse(ApiException(405, "Method not allowed", "OPTIONS ${req.uri.path}"), null)

        is CorsPreflight.Refused -> errorResponse(ApiException(403, "Forbidden", decision.reason), null)

        is CorsPreflight.Allowed ->
            decision.headers
                .fold(Response(Status.NO_CONTENT)) { res, (name, value) -> res.header(name, value) }
    }

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
            ).associateTo(IdentityHashMap<Any, BodyCodec<Any?>>()) { it }
    }.orEmpty(),
)

/**
 * More specific paths are matched first, so `/orders/watch` wins over
 * `/orders/{orderId}` no matter which was declared first. Ties keep
 * declaration order, which stays predictable.
 *
 * http4k tries routes in the order they are given, so this is the same sort
 * `pelican-pekko` applies for the same reason.
 */
private fun Api.orderedEndpoints(): List<ServerEndpoint> =
    endpoints.withIndex().sortedWith(
        compareByDescending<IndexedValue<ServerEndpoint>> { (_, se) ->
            se.endpoint.pathSpec.segments.count { it is PathSegment.Literal }
        }.thenBy { it.index },
    ).map { it.value }

private fun routeFor(
    se: ServerEndpoint,
    api: Api,
    codecs: EndpointCodecs,
    bound: (Params) -> java.util.concurrent.CompletionStage<Any?>,
    cors: CorsPolicy?,
): RoutingHttpHandler {
    val handler: HttpHandler = { req -> invoke(se, api, codecs, bound, req).withCors(cors, req) }
    return se.endpoint.pathSpec.template bind se.endpoint.method.toHttp4k() to handler
}

/**
 * Adds the cross-origin headers to a finished response, whatever produced it —
 * a handler, a decode failure, an exception. A browser needs them on the error
 * as much as on the success: without them the script sees a network error
 * instead of the 400 that explains itself.
 */
private fun Response.withCors(cors: CorsPolicy?, req: Request): Response {
    if (cors == null) return this
    return cors.actualResponseHeaders(req.header(CorsHeaders.ORIGIN))
        .fold(this) { res, (name, value) -> res.header(name, value) }
}

/**
 * Everything decodable before the body, as values rather than as writes into a
 * map somebody else owns. One rule, written once per input kind because what
 * "present" means differs between a query parameter, a header and a cookie
 * core parsed out of the header itself.
 */
private fun decodePlainInputs(ep: Endpoint<*, *>, req: Request, into: MutableMap<ParamKey<*>, Any?>) = with(into) {
    // A loop rather than `filterIsInstance`, which would allocate a list per
    // request to hold what is walked once.
    ep.pathSpec.segments.forEach { segment ->
        if (segment is PathSegment.Capture) {
            val param = segment.param
            // Present by construction: this handler only runs for a request the
            // template matched, and the template's captures are these params.
            val raw = req.path(param.name)
                ?: error("$ep matched ${req.uri.path} but captured no '${param.name}'")
            put(param, param.codec.decode(param.name, raw))
        }
    }

    ep.queries.forEach { q ->
        val style = q.listStyle
        if (style != null) {
            put(q, q.decodeList(req.queries(q.name).filterNotNull()))
            return@forEach
        }
        val raw = req.query(q.name)
        put(
            q,
            when {
                raw != null -> q.codec.decode(q.name, raw)
                q.required -> throw ApiException(400, "Missing required query parameter '${q.name}'")
                else -> q.default
            },
        )
    }

    ep.headerParams.forEach { h ->
        // `header` returns the first field line; a list is declared as
        // comma-separated and RFC 9110 says two lines mean the one joined
        // field, so it is the only case that has to read them all.
        if (h.listStyle != null) {
            put(h, h.decodeList(req.headerValues(h.name).filterNotNull()))
            return@forEach
        }
        val raw = req.header(h.name)
        put(
            h,
            when {
                raw != null -> h.codec.decode(h.name, raw)
                h.required -> throw ApiException(400, "Missing required header '${h.name}'")
                else -> h.default
            },
        )
    }

    decodeCookies(ep, req, into)
}

/**
 * Cookies, parsed by core from the header rather than by http4k's own cookie
 * support — so a cookie decodes to the same value on all three backends. See
 * `Cookies`.
 *
 * Skipped entirely when nothing declared one: reading the header and parsing
 * it costs the same whether or not anybody asked for a cookie.
 */
private fun decodeCookies(ep: Endpoint<*, *>, req: Request, into: MutableMap<ParamKey<*>, Any?>) {
    if (ep.cookieParams.isEmpty()) return

    val cookies = Cookies.parseAll(req.headerValues("Cookie").filterNotNull())
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
 * The body, read the way its declaration says to: not at all, handed over
 * unconsumed, parsed as a multipart envelope, or pulled into a string for a
 * codec. Decoded values go into [values]; a refusal is thrown, and the caller
 * turns it into a response through core's `renderError`.
 */

/** One place to refuse an oversized body, so both checks answer alike. */
private fun refuseIfOversize(length: Long?, limit: Long) {
    if (length != null && length > limit) throw PayloadTooLarge(limit)
}

private fun readBody(
    ep: Endpoint<*, *>,
    api: Api,
    codecs: EndpointCodecs,
    req: Request,
    values: MutableMap<ParamKey<*>, Any?>,
) {
    when (val body = ep.bodyInput) {
        null -> Unit

        // Handed over unconsumed. http4k's own body is already lazy, so a
        // handler that never reads it never pulls the request into memory.
        is RawBody -> values[body] = Http4kByteStream(req.body)

        // Exempt from the size limit for the same reason a raw body is: the
        // streamed part is never held whole. What the parts that *are* held
        // may cost is bounded, and that is what the limit is passed in for.
        is MultipartBody -> body.decode(
            contentType = req.header("Content-Type"),
            input = req.body.stream,
            maxInMemoryBytes = api.maxBodyBytes,
            into = values,
        )

        is JsonBody<*>, is FormBody<*>, is NegotiatedBody<*> -> {
            // Checked before the body is pulled into a String, so an
            // oversized payload is refused rather than allocated — and again
            // on what arrived, because a request may declare no length.
            refuseIfOversize(req.header("Content-Length")?.toLongOrNull(), api.maxBodyBytes)
            val text = req.bodyString()
            refuseIfOversize(text.length.toLong(), api.maxBodyBytes)
            // Which codec, and what a media type nobody declared means, are
            // core's answers — see `RequestBodyCodecs`. So is wrapping whatever
            // the codec threw, which is what keeps this file codec-agnostic.
            values[body] = checkNotNull(codecs.body) { "No codec was resolved for the body of $ep" }
                .decode(req.header("Content-Type"), text)
        }
    }
}

private fun invoke(
    se: ServerEndpoint,
    api: Api,
    codecs: EndpointCodecs,
    bound: (Params) -> java.util.concurrent.CompletionStage<Any?>,
    req: Request,
): Response {
    val ep = se.endpoint
    // Sized to what the endpoint declares. The default of 16 buckets is a
    // 144-byte table for the two or three inputs an endpoint usually has.
    val values = LinkedHashMap<ParamKey<*>, Any?>(ep.declaredInputCount())

    // Built before decoding, so a filter or a failing decode can still put a
    // header on the way out.
    val params = Params(values, req, ep)

    // ---- inputs: path, query, headers, body -------------------------------
    try {
        decodePlainInputs(ep, req, values)

        readBody(ep, api, codecs, req, values)
    } catch (t: Throwable) {
        return errorResponse(t, api, ep).withHeaders(params)
    }

    // ---- the handler ------------------------------------------------------
    //
    // http4k answers on the calling thread, so a handler bound with one of the
    // stage-taking binders is waited for here rather than handed back to the
    // server. `strictBodyTimeoutMillis` has no equivalent: reading the body is
    // a blocking read the server's own read timeout governs.
    return try {
        val result = bound(params).toCompletableFuture().join()
        buildResponse(ep.output, result, codecs)
    } catch (t: Throwable) {
        errorResponse(t, api, ep)
    }.withHeaders(params)
}

/** What the handler asked for, on whatever response came back. */
private fun Response.withHeaders(params: Params): Response =
    params.responseHeaders().fold(this) { res, (name, value) -> res.header(name, value) }
