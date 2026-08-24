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
import io.github.matthewjones372.pelican.Output
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.PathSegment
import io.github.matthewjones372.pelican.PayloadTooLarge
import io.github.matthewjones372.pelican.RawBody
import io.github.matthewjones372.pelican.RequestBodyCodecs
import io.github.matthewjones372.pelican.ServerEndpoint
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
 * Interprets an [Api] as Ktor routes.
 *
 * Each endpoint becomes `route(template, method) { handle { ... } }`, so path
 * matching, template extraction and the 404/405 rules are Ktor's own — driven
 * by the descriptions instead of hand-written route declarations. An endpoint's
 * [io.github.matthewjones372.pelican.PathSpec.template] is already `/users/{userId}`, which is
 * exactly Ktor's template syntax, so the two agree by construction rather than
 * by translation.
 *
 * Nothing is sorted here, unlike the other two backends. Ktor resolves a
 * request against a routing *tree* and scores a constant segment above a
 * parameter, so `/orders/watch` wins over `/orders/{orderId}` whichever was
 * declared first. `RoutingTest` states that as a test rather than trusting it.
 *
 * This is a `Route` extension because that is the unit a Ktor service already
 * composes: put Pelican's endpoints under an `authenticate { }` block, behind a
 * `route("/v2")`, or next to routes written by hand, and the surrounding
 * plugins apply to them like any others.
 *
 * ```
 * embeddedServer(CIO, port = 8080) {
 *     install(CallLogging)
 *     routing {
 *         pelican(ordersApi())
 *     }
 * }.start(wait = true)
 * ```
 *
 * Endpoints only. Nothing here generates or serves an OpenAPI document, which
 * is why a service can depend on this module without the doc generator being
 * compiled in at all — see `pelican-ktor-docs` for `startWithDocs`.
 */
fun Route.pelican(api: Api) {
    require(api.endpoints.isNotEmpty()) { "This API has no endpoints." }

    // Worked out once, from the descriptions, and captured by the routes — the
    // same shape as the codecs below and for the same reason.
    val cors = api.corsPolicy()

    // Codecs are resolved here, once per endpoint, and captured by the routes.
    // KType -> JavaType reflection is not free, and doing it per request would
    // put it on the hot path for no benefit. It also means a missing or broken
    // codec is a startup failure rather than a surprise on the first request.
    for (se in api.endpoints) {
        val codecs = se.endpoint.resolveCodecs(api.codecs)
        // Filters are folded around the handler here, once, rather than per
        // request — the same reasoning as the codecs above.
        val bound = api.handlerFor(se)
        route(se.endpoint.pathSpec.template, se.endpoint.method.toKtor()) {
            handle {
                // Before anything responds: Ktor sends the headers with the
                // first byte of the body, and a streamed response starts
                // writing that body as soon as its first frame is encoded.
                call.addCorsHeaders(cors)
                invoke(se, api, codecs, bound, call)
            }
        }
    }

    preflightRoutes(api, cors)
}

/**
 * One `OPTIONS` route per declared path, which is what a browser asks before it
 * sends anything interesting.
 *
 * A path that already declares an `OPTIONS` endpoint of its own keeps it: an
 * endpoint someone wrote down outranks one this module would have invented.
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
        // Not a preflight — a bare OPTIONS, or one aimed at no described path.
        // Nothing here describes an answer to that, so it gets the same 404
        // Ktor's own router gives a method it does not recognise on a path it
        // does. See `MethodMismatchTest` for why that number is Ktor's, not
        // Pelican's.
        is CorsPreflight.NotPreflight -> respondError(ApiException(404, "Not found"), null)

        is CorsPreflight.Refused -> respondError(ApiException(403, "Forbidden", decision.reason), null)

        is CorsPreflight.Allowed -> {
            decision.headers.forEach { (name, value) -> response.headers.append(name, value) }
            respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Adds the cross-origin headers to whatever this call ends up answering — a
 * handler's value, a decode failure, an exception. A browser needs them on the
 * error as much as on the success: without them the script sees a network error
 * instead of the 400 that explains itself.
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
 * Everything decodable before the body: path captures, query parameters,
 * headers, cookies.
 *
 * Decoded straight into the request's own value bag rather than into a map of
 * its own that the caller then copies — see the http4k interpreter, where that
 * copy measured at roughly 70ns per request.
 */
private fun decodePlainInputs(
    ep: Endpoint<*, *>,
    call: ApplicationCall,
    into: MutableMap<ParamKey<*>, Any?>,
) = with(into) {
    // A loop rather than `filterIsInstance`, which would allocate a list per
    // request to hold what is walked once.
    ep.pathSpec.segments.forEach { segment ->
        if (segment is PathSegment.Capture) {
            val param = segment.param
            // Present by construction: this handler only runs for a request the
            // template matched, and the template's captures are these params.
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
        // The subscript is the first field line; a list is declared as
        // comma-separated and RFC 9110 says two lines mean the one joined
        // field, so it is the only case that has to read them all.
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
 * Cookies, parsed by core from the header rather than by Ktor's own cookie
 * support — so a cookie decodes to the same value on all three backends. See
 * `Cookies`. Skipped when nothing declared one: reading and parsing the header
 * costs the same whether or not anybody asked for a cookie.
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

        // The one place this backend reads a body with a blocking stream
        // rather than a channel. Core parses the envelope, because three
        // parsers would be three sets of behaviour, and core's is a
        // `java.io.InputStream` — so the read moves to the IO dispatcher
        // rather than parking a thread the engine wanted. The handler's
        // own read of the file part is blocking for the same reason, which
        // is the honest cost of one parser instead of three.
        is MultipartBody -> withContext(Dispatchers.IO) {
            body.decode(
                contentType = call.request.headers[HttpHeaders.ContentType],
                input = call.receiveChannel().toInputStream(),
                maxInMemoryBytes = api.maxBodyBytes,
                into = values,
            )
        }

        is JsonBody<*>, is FormBody<*>, is NegotiatedBody<*> -> {
            // The one place a slow client is this module's problem: a
            // strict body has to arrive in full before the handler can be
            // called, so it gets the API's own deadline rather than the
            // engine's idle timeout.
            // Checked before the body is pulled into a String, so an
            // oversized payload is refused rather than allocated.
            refuseIfOversize(call.request.headers["Content-Length"]?.toLongOrNull(), api.maxBodyBytes)
            val text = try {
                withTimeout(api.strictBodyTimeoutMillis) { call.receiveText() }
            } catch (t: TimeoutCancellationException) {
                throw ApiException(408, "Timed out reading the request body", t.message, cause = t)
            }
            refuseIfOversize(text.length.toLong(), api.maxBodyBytes)
            // Which codec, and what a media type nobody declared means, are
            // core's answers — see `RequestBodyCodecs`. So is wrapping whatever
            // the codec threw, which is what keeps this file codec-agnostic.
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

    // ---- inputs: path, query, headers, body -------------------------------
    try {
        decodePlainInputs(ep, call, values)

        readBody(ep, api, codecs, call, values)
    } catch (t: Throwable) {
        call.applyHeaders(params)
        return call.respondError(t, api, ep)
    }

    // ---- the handler ------------------------------------------------------
    //
    // The handler was launched as a child of this call (see `Handlers.kt`), so
    // awaiting it here suspends rather than parks a thread, and a client that
    // goes away cancels it. Whatever it throws is rendered, exactly as on the
    // other backends — nothing is left for Ktor's own error handling to catch,
    // which is what keeps an ApiException a 404 rather than a stack trace.
    val result = try {
        bound(params).await()
    } catch (t: CancellationException) {
        // The client went away, or the server is shutting down. There is nobody
        // left to answer, and pretending otherwise would swallow a cancellation
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
        // A stream that fails after its first element has already committed a
        // 200 and some bytes; there is no status left to change. Let it out, so
        // the engine tears the connection down and the client sees a truncated
        // response rather than a well-formed lie.
        if (call.response.isCommitted) throw t
        call.respondError(t, api, ep)
    }
}

/** What the handler asked for, before anything is committed. */
private fun ApplicationCall.applyHeaders(params: Params) {
    params.responseHeaders().forEach { (name, value) -> response.headers.append(name, value) }
}
