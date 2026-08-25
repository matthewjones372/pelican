package io.github.matthewjones372.pelican.ktor

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
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.MultipartBody
import io.github.matthewjones372.pelican.NegotiatedBody
import io.github.matthewjones372.pelican.NotAcceptable
import io.github.matthewjones372.pelican.Output
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.PayloadTooLarge
import io.github.matthewjones372.pelican.RawBody
import io.github.matthewjones372.pelican.RequestBodyCodecs
import io.github.matthewjones372.pelican.RouteIndex
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.acceptable
import io.github.matthewjones372.pelican.corsPolicy
import io.github.matthewjones372.pelican.decode
import io.github.matthewjones372.pelican.decodeList
import io.github.matthewjones372.pelican.handlerFor
import io.github.matthewjones372.pelican.readStrictBody
import io.github.matthewjones372.pelican.requestBodyCodec
import io.github.matthewjones372.pelican.routeIndex
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.IdentityHashMap
import java.util.concurrent.CompletionStage

/**
 * Interprets an [Api] as Ktor routes: one route per method the descriptions
 * use, each dispatching through the same [RouteIndex] the other two backends
 * walk, so a request line means one thing whichever server reads it.
 *
 * Ktor's own tree used to do the matching, from `PathSpec.template`. It decoded
 * a segment its own way, applied its own trailing-slash rule and could not be
 * asked what the other two would have answered — three routers to keep in step
 * rather than one to prove. What is still Ktor's is where a request lands: a
 * constant segment scores above a tailcard, so routes written by hand beside
 * these keep winning the paths they describe, and a path nothing here describes
 * is the same 404 as before.
 *
 * A `Route` extension, because that is the unit a Ktor service composes — put
 * the endpoints under `authenticate { }`, behind a `route("/v2")`, or beside
 * routes written by hand.
 *
 * Endpoints only. See `pelican-ktor-docs` for `startWithDocs`.
 */
fun Route.pelican(api: Api) {
    require(api.endpoints.isNotEmpty()) { "This API has no endpoints." }

    // Worked out once from the descriptions, as with the codecs below.
    val cors = api.corsPolicy()

    // Once per endpoint, captured by the routes: KType -> JavaType reflection
    // is not free, and a broken codec becomes a startup failure.
    val codecs = api.endpoints.associateWith { it.endpoint.resolveCodecs(api.codecs) }

    // Folded around each handler once rather than per request, likewise.
    val handlers = api.endpoints.associateWith { api.handlerFor(it) }

    val index = api.endpoints.routeIndex()

    // Per method rather than one route overall, so a verb no endpoint declares
    // anywhere never reaches this and stays Ktor's own 404. The extra `OPTIONS`
    // is the preflight a browser sends; an API declaring `OPTIONS` itself is
    // dispatched through the index like anything else.
    val declared = api.endpoints.map { it.endpoint.method }.distinct()
    val methods = if (cors == null || Method.OPTIONS in declared) declared else declared + Method.OPTIONS

    methods.forEach { method ->
        route(ANY_PATH, method.toKtor()) {
            handle { call.dispatch(method, api, index, codecs, handlers, cors) }
        }
    }
}

/**
 * Every path under this route, because the index is what decides which endpoint
 * answers. Unnamed, so Ktor does not build a parameter list nothing reads.
 */
private const val ANY_PATH = "{...}"

/** The endpoint the index finds, the preflight nobody described, or a 404. */
@Suppress("LongParameterList") // The route's whole world, resolved once and captured.
private suspend fun ApplicationCall.dispatch(
    method: Method,
    api: Api,
    index: RouteIndex,
    codecs: Map<ServerEndpoint, EndpointCodecs>,
    handlers: Map<ServerEndpoint, (Params) -> CompletionStage<Any?>>,
    cors: CorsPolicy?,
) {
    val values = LinkedHashMap<ParamKey<*>, Any?>()

    // The raw request path: the index splits on `/` before it decodes anything,
    // so an encoded slash stays inside the segment that carried it. A capture
    // that will not decode, or an escape that is not one, is a 400 naming what
    // was wrong rather than somebody else's 404.
    val matched = try {
        index.match(method, request.path(), values)
    } catch (t: Throwable) {
        return respondError(t, api)
    }

    when {
        matched != null -> {
            // Ktor sends headers with the first byte of the body, and a stream
            // writes that as soon as its first frame is encoded.
            addCorsHeaders(cors)
            invoke(matched, api, codecs.getValue(matched), handlers.getValue(matched), this, values)
        }

        method == Method.OPTIONS && cors != null -> respondPreflight(cors)

        else -> respond(HttpStatusCode.NotFound)
    }
}

private suspend fun ApplicationCall.respondPreflight(cors: CorsPolicy) {
    when (
        val decision = cors.preflight(
            origin = request.headers[CorsHeaders.ORIGIN],
            requestMethod = request.headers[CorsHeaders.REQUEST_METHOD],
            path = request.path(),
        )
    ) {
        // A bare OPTIONS, or one aimed at no described path: the same 404
        // Ktor's own router gives. See `MethodMismatchTest`.
        is CorsPreflight.NotPreflight -> respondError(ApiException(404, "Not found"), null)

        is CorsPreflight.Refused -> respondError(ApiException(403, "Forbidden", decision.reason), null)

        is CorsPreflight.Allowed -> {
            decision.headers.forEach { (name, value) -> response.headers.append(name, value) }
            respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Cross-origin headers on whatever this call answers, errors included: without
 * them a browser script sees a network error rather than the 400.
 */
private fun ApplicationCall.addCorsHeaders(cors: CorsPolicy?) {
    cors?.actualResponseHeaders(request.headers[CorsHeaders.ORIGIN])
        ?.forEach { (name, value) -> response.headers.append(name, value) }
}

/** Installs the routing plugin and the endpoints in one step. */
fun Application.pelican(api: Api) {
    routing { pelican(api) }
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
 * Everything decodable before the body, written straight into the request's own
 * value bag rather than a map the caller then copies — that copy measured at
 * roughly 70ns per request on http4k.
 */

/**
 * Refuses a request whose `Accept` takes nothing this endpoint sends. The types
 * come from [Output.produces] and the decision from [acceptable], so the three
 * backends answer alike. An endpoint with no body never reads the header.
 */
private fun negotiate(ep: Endpoint<*, *>, call: ApplicationCall) {
    val produced = ep.output.produces
    if (produced.isEmpty()) return
    val accept = call.request.headers.getAll("Accept").orEmpty()
    if (accept.isEmpty()) return
    if (!acceptable(accept, produced)) throw NotAcceptable(produced)
}

private fun decodePlainInputs(
    ep: Endpoint<*, *>,
    call: ApplicationCall,
    into: MutableMap<ParamKey<*>, Any?>,
) = with(into) {
    // Path captures are already here: the index decoded them as it matched.

    ep.queries.forEach { q ->
        val style = q.listStyle
        if (style != null) {
            put(q, q.decodeList(call.request.queryParameters.getAll(q.name).orEmpty()))
            return@forEach
        }
        val raw = call.request.queryParameters[q.name]
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
        // The subscript is the first line only, and RFC 9110 says two lines
        // mean one joined field — so a list is the case that reads them all.
        if (h.listStyle != null) {
            put(h, h.decodeList(call.request.headers.getAll(h.name).orEmpty()))
            return@forEach
        }
        val raw = call.request.headers[h.name]
        put(
            h,
            when {
                raw != null -> h.codec.decode(h.name, raw)
                h.required -> throw ApiException(400, "Missing required header '${h.name}'")
                else -> h.default
            },
        )
    }

    decodeCookies(ep, call, into)
}

/**
 * Parsed by core rather than by Ktor's own cookie support, so a cookie decodes
 * the same on all three backends. Skipped when nothing declared one.
 */
private fun decodeCookies(ep: Endpoint<*, *>, call: ApplicationCall, into: MutableMap<ParamKey<*>, Any?>) {
    if (ep.cookieParams.isEmpty()) return

    val cookies = Cookies.parseAll(call.request.headers.getAll("Cookie").orEmpty())
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
 * The body, read the way its declaration says to: not at all, handed over as a
 * channel, parsed as a multipart envelope on the IO dispatcher, or pulled into
 * a string for a codec. Decoded values go into [values]; a refusal is thrown,
 * and the caller turns it into a response through core's `renderError`.
 */

/** One place to refuse an oversized body, so every check answers alike. */
private fun refuseIfOversize(length: Long?, limit: Long) {
    if (length != null && length > limit) throw PayloadTooLarge(limit)
}

private suspend fun readBody(
    ep: Endpoint<*, *>,
    api: Api,
    codecs: EndpointCodecs,
    call: ApplicationCall,
    values: MutableMap<ParamKey<*>, Any?>,
) {
    when (val body = ep.bodyInput) {
        null -> Unit

        // Handed over unconsumed: no buffering, full back-pressure.
        is RawBody -> values[body] = KtorByteStream(call.receiveChannel())

        // The one blocking read on this backend. Core parses the envelope
        // from a `java.io.InputStream`, so it moves to the IO dispatcher
        // rather than parking a thread the engine wanted — the cost of one
        // parser instead of three.
        is MultipartBody -> withContext(Dispatchers.IO) {
            body.decode(
                contentType = call.request.headers[HttpHeaders.ContentType],
                input = call.receiveChannel().toInputStream(),
                maxInMemoryBytes = api.maxBodyBytes,
                into = values,
            )
        }

        is JsonBody<*>, is FormBody<*>, is NegotiatedBody<*> -> {
            // A declared length is refused before a byte is transferred; a
            // chunked body that declares none is counted as it is read. Both
            // are bytes — `String.length` is UTF-16 code units, and a limit
            // checked against it admits about three times as much CJK as it
            // promises. `readStrictBody` blocks on the channel, so it goes to
            // the IO dispatcher for the reason the multipart read does.
            refuseIfOversize(call.request.headers["Content-Length"]?.toLongOrNull(), api.maxBodyBytes)
            val text = try {
                withTimeout(api.strictBodyTimeoutMillis) {
                    withContext(Dispatchers.IO) {
                        readStrictBody(call.receiveChannel().toInputStream(), api.maxBodyBytes)
                    }
                }
            } catch (t: TimeoutCancellationException) {
                throw ApiException(408, "Timed out reading the request body", t.message, cause = t)
            }
            // Which codec, and what an undeclared media type means, are core's
            // answers — see `RequestBodyCodecs`. So is wrapping what it threw.
            values[body] = checkNotNull(codecs.body) { "No codec was resolved for the body of $ep" }
                .decode(call.request.headers[HttpHeaders.ContentType], text)
        }
    }
}

@Suppress("LongParameterList") // One request's world, threaded rather than held.
private suspend fun invoke(
    se: ServerEndpoint,
    api: Api,
    codecs: EndpointCodecs,
    bound: (Params) -> CompletionStage<Any?>,
    call: ApplicationCall,
    values: MutableMap<ParamKey<*>, Any?>,
) {
    val ep = se.endpoint

    // Built before decoding, so a filter or a failing decode can still put a
    // header on the way out.
    val params = Params(values, call, ep)

    // ---- what the caller will take, then the inputs -----------------------
    //
    // Negotiation first, so a caller who will not read what this endpoint sends
    // is refused before the handler does the work.
    try {
        negotiate(ep, call)

        decodePlainInputs(ep, call, values)

        readBody(ep, api, codecs, call, values)
    } catch (t: Throwable) {
        call.applyHeaders(params)
        return call.respondError(t, api, ep)
    }

    // ---- the handler ------------------------------------------------------
    //
    // Launched as a child of this call (see `Handlers.kt`), so awaiting it
    // suspends rather than parks a thread and a disconnect cancels it. What it
    // throws is rendered here, leaving nothing for Ktor's error handling to
    // turn an ApiException into a stack trace.
    val result = try {
        bound(params).await()
    } catch (t: CancellationException) {
        // Nobody left to answer, and swallowing it would hide a cancellation
        // Ktor needs to see.
        throw t
    } catch (t: Throwable) {
        call.applyHeaders(params)
        return call.respondError(t, api, ep)
    }

    // Ktor sends headers with the first byte of the body, so this is the last
    // moment they can go on.
    call.applyHeaders(params)

    try {
        respond(call, ep.output, result, codecs)
    } catch (t: Throwable) {
        // A stream failing after its first element has already committed a 200
        // and some bytes. Let it out, so the client sees a truncated response
        // rather than a well-formed lie.
        if (call.response.isCommitted) throw t
        call.respondError(t, api, ep)
    }
}

/** What the handler asked for, before anything is committed. */
private fun ApplicationCall.applyHeaders(params: Params) {
    params.responseHeaders().forEach { (name, value) -> response.headers.append(name, value) }
}
