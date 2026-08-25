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
import io.github.matthewjones372.pelican.PathSegment
import io.github.matthewjones372.pelican.PayloadTooLarge
import io.github.matthewjones372.pelican.RawBody
import io.github.matthewjones372.pelican.RequestBodyCodecs
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.acceptable
import io.github.matthewjones372.pelican.corsPolicy
import io.github.matthewjones372.pelican.decode
import io.github.matthewjones372.pelican.decodeList
import io.github.matthewjones372.pelican.handlerFor
import io.github.matthewjones372.pelican.requestBodyCodec
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
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

/**
 * Interprets an [Api] as Ktor routes: each endpoint becomes
 * `route(template, method) { handle { ... } }`, so matching and the 404/405
 * rules are Ktor's own. A [PathSpec.template] is already Ktor's syntax, so the
 * two agree by construction rather than by translation.
 *
 * Nothing is sorted here, unlike the other two backends: Ktor's routing tree
 * scores a constant segment above a parameter. `RoutingTest` asserts that.
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
    for (se in api.endpoints) {
        val codecs = se.endpoint.resolveCodecs(api.codecs)
        // Folded around the handler once rather than per request, likewise.
        val bound = api.handlerFor(se)
        route(se.endpoint.pathSpec.template, se.endpoint.method.toKtor()) {
            handle {
                // Ktor sends headers with the first byte of the body, and a
                // stream writes that as soon as its first frame is encoded.
                call.addCorsHeaders(cors)
                invoke(se, api, codecs, bound, call)
            }
        }
    }

    preflightRoutes(api, cors)
}

/**
 * One `OPTIONS` route per declared path, for a browser's preflight. A path that
 * declares its own `OPTIONS` endpoint keeps it.
 */
private fun Route.preflightRoutes(api: Api, cors: CorsPolicy?) {
    if (cors == null) return

    val declaresOptions = api.endpoints
        .filter { it.endpoint.method == Method.OPTIONS }
        .map { it.endpoint.pathSpec.template }
        .toSet()

    api.endpoints
        .map { it.endpoint.pathSpec.template }
        .distinct()
        .filterNot { it in declaresOptions }
        .forEach { template ->
            route(template, HttpMethod.Options) {
                handle { call.respondPreflight(cors) }
            }
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
    // A loop rather than `filterIsInstance`, which allocates a list per
    // request to hold what is walked once.
    ep.pathSpec.segments.forEach { segment ->
        if (segment is PathSegment.Capture) {
            val param = segment.param
            // Present by construction: this handler only runs for a request
            // the template matched.
            val raw = call.parameters[param.name]
                ?: error("$ep matched ${call.request.local.uri} but captured no '${param.name}'")
            put(param, param.codec.decode(param.name, raw))
        }
    }

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
            refuseIfOversize(call.request.headers["Content-Length"]?.toLongOrNull(), api.maxBodyBytes)
            val text = try {
                withTimeout(api.strictBodyTimeoutMillis) { call.receiveText() }
            } catch (t: TimeoutCancellationException) {
                throw ApiException(408, "Timed out reading the request body", t.message, cause = t)
            }
            refuseIfOversize(text.length.toLong(), api.maxBodyBytes)
            // Which codec, and what an undeclared media type means, are core's
            // answers — see `RequestBodyCodecs`. So is wrapping what it threw.
            values[body] = checkNotNull(codecs.body) { "No codec was resolved for the body of $ep" }
                .decode(call.request.headers[HttpHeaders.ContentType], text)
        }
    }
}

private suspend fun invoke(
    se: ServerEndpoint,
    api: Api,
    codecs: EndpointCodecs,
    bound: (Params) -> java.util.concurrent.CompletionStage<Any?>,
    call: ApplicationCall,
) {
    val ep = se.endpoint
    val values = LinkedHashMap<ParamKey<*>, Any?>()

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
