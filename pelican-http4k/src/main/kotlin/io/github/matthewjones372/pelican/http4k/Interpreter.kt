package io.github.matthewjones372.pelican.http4k

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.Cookies
import io.github.matthewjones372.pelican.CorsHeaders
import io.github.matthewjones372.pelican.CorsPolicy
import io.github.matthewjones372.pelican.CorsPreflight
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.FallibleOutput
import io.github.matthewjones372.pelican.FormBody
import io.github.matthewjones372.pelican.JsonBody
import io.github.matthewjones372.pelican.MultipartBody
import io.github.matthewjones372.pelican.NegotiatedBody
import io.github.matthewjones372.pelican.NotAcceptable
import io.github.matthewjones372.pelican.Output
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.PathSegment
import io.github.matthewjones372.pelican.PayloadTooLarge
import io.github.matthewjones372.pelican.RawBody
import io.github.matthewjones372.pelican.RequestBodyCodecs
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.acceptable
import io.github.matthewjones372.pelican.corsPolicy
import io.github.matthewjones372.pelican.declaredInputCount
import io.github.matthewjones372.pelican.decode
import io.github.matthewjones372.pelican.decodeList
import io.github.matthewjones372.pelican.handlerFor
import io.github.matthewjones372.pelican.requestBodyCodec
import org.http4k.core.HttpHandler
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes
import java.util.IdentityHashMap
import io.github.matthewjones372.pelican.Method as PelicanMethod
import org.http4k.core.Method as Http4kMethod

/**
 * Interprets an [Api] as an http4k [HttpHandler]: each endpoint becomes
 * `template bind method to { ... }`, combined with `routes(...)`, so matching
 * and the 404/405 rules are http4k's own. A [PathSpec.template] is already
 * http4k's syntax, so the two agree by construction rather than by translation.
 *
 * The result is a plain function from `Request` to `Response`, which is the
 * whole in-memory test story for this backend.
 *
 * Endpoints only. See `pelican-http4k-docs` for `startWithDocs`.
 */
fun Api.toHttpHandler(): RoutingHttpHandler {
    // Once per endpoint, captured by the routes: KType -> JavaType reflection
    // is not free, and a broken codec becomes a startup failure.
    val resolved = endpoints.associateWith { it.endpoint.resolveCodecs(this.codecs) }

    // Worked out once from the descriptions, as with the codecs above.
    val cors = corsPolicy()

    // Folded around each handler once rather than per request, likewise.
    val handlers = endpoints.associateWith { handlerFor(it) }

    val ordered = orderedEndpoints()
    val routes = ordered.map { routeFor(it, this, resolved.getValue(it), handlers.getValue(it), cors) } +
        preflightRoutes(ordered, cors)
    require(routes.isNotEmpty()) { "This API has no endpoints." }
    return routes(routes)
}

/**
 * One `OPTIONS` route per declared path, for a browser's preflight. A path that
 * declares its own `OPTIONS` endpoint keeps it, and these come last because
 * http4k tries routes in order.
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
        // A bare OPTIONS, or one aimed at no described path: what the router
        // would have said before this route existed.
        is CorsPreflight.NotPreflight ->
            errorResponse(ApiException(405, "Method not allowed", "OPTIONS ${req.uri.path}"), null)

        is CorsPreflight.Refused -> errorResponse(ApiException(403, "Forbidden", decision.reason), null)

        is CorsPreflight.Allowed ->
            decision.headers
                .fold(Response(Status.NO_CONTENT)) { res, (name, value) -> res.header(name, value) }
    }

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
    alternatives = (output as? FallibleOutput<*, *>)?.let { declared ->
        (
            declared.successes.mapNotNull { s -> s.payloadType?.let { s as Any to codecs.codec<Any?>(it) } } +
                declared.failures.map { f -> f as Any to codecs.codec<Any?>(f.type) }
            ).associateTo(IdentityHashMap<Any, BodyCodec<Any?>>()) { it }
    }.orEmpty(),
)

/**
 * More specific paths first, so `/orders/watch` wins over `/orders/{orderId}`
 * whichever was declared first. http4k tries routes in order, so this is the
 * same sort `pelican-pekko` applies.
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
 * Cross-origin headers on a finished response, errors included: without them a
 * browser script sees a network error rather than the 400.
 */
private fun Response.withCors(cors: CorsPolicy?, req: Request): Response {
    if (cors == null) return this
    return cors.actualResponseHeaders(req.header(CorsHeaders.ORIGIN))
        .fold(this) { res, (name, value) -> res.header(name, value) }
}

/**
 * Everything decodable before the body. One rule, written once per input kind
 * because "present" differs between a query parameter, a header and a cookie
 * core parsed out of the header itself.
 */

/**
 * Refuses a request whose `Accept` takes nothing this endpoint sends. The types
 * come from [Output.produces] and the decision from [acceptable], so the three
 * backends answer alike. An endpoint with no body never reads the header.
 */
private fun negotiate(ep: Endpoint<*, *>, req: Request) {
    val produced = ep.output.produces
    if (produced.isEmpty()) return
    val accept = req.headerValues("Accept").filterNotNull()
    if (accept.isEmpty()) return
    if (!acceptable(accept, produced)) throw NotAcceptable(produced)
}

private fun decodePlainInputs(ep: Endpoint<*, *>, req: Request, into: MutableMap<ParamKey<*>, Any?>) = with(into) {
    // A loop rather than `filterIsInstance`, which allocates a list per
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
        // `header` returns the first line only, and RFC 9110 says two lines
        // mean one joined field — so a list is the case that reads them all.
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
 * Parsed by core rather than by http4k's own cookie support, so a cookie
 * decodes the same on all three backends. Skipped when nothing declared one.
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

        // http4k's own body is lazy, so a handler that never reads it never
        // pulls the request into memory.
        is RawBody -> values[body] = Http4kByteStream(req.body)

        // Exempt from the size limit as a raw body is: the streamed part is
        // never held whole, and the limit bounds the parts that are.
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
    // Sized to the declaration: the default 16 buckets is a 144-byte table
    // for the two or three inputs an endpoint usually has.
    val values = LinkedHashMap<ParamKey<*>, Any?>(ep.declaredInputCount())

    // Built before decoding, so a filter or a failing decode can still put a
    // header on the way out.
    val params = Params(values, req, ep)

    // ---- what the caller will take, then the inputs -----------------------
    //
    // Negotiation first, so a caller who will not read what this endpoint sends
    // is refused before the handler does the work.
    try {
        negotiate(ep, req)

        decodePlainInputs(ep, req, values)

        readBody(ep, api, codecs, req, values)
    } catch (t: Throwable) {
        return errorResponse(t, api, ep).withHeaders(params)
    }

    // ---- the handler ------------------------------------------------------
    //
    // http4k answers on the calling thread, so a stage-taking binder is waited
    // for here. `strictBodyTimeoutMillis` has no equivalent: the body is a
    // blocking read the server's own timeout governs.
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
