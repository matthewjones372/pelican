package io.github.matthewjones372.pelican.gannet

import io.github.matthewjones372.gannet.HttpExchange
import io.github.matthewjones372.gannet.HttpRequest
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.BodyCodec
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
import io.github.matthewjones372.pelican.Pages
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.RawBody
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.SseOutput
import io.github.matthewjones372.pelican.corsPolicy
import io.github.matthewjones372.pelican.spi.CorsHeaders
import io.github.matthewjones372.pelican.spi.RequestBodyCodecs
import io.github.matthewjones372.pelican.spi.RouteIndex
import io.github.matthewjones372.pelican.spi.acceptable
import io.github.matthewjones372.pelican.spi.decodeCaller
import io.github.matthewjones372.pelican.spi.handlerFor
import io.github.matthewjones372.pelican.spi.ndjsonFrames
import io.github.matthewjones372.pelican.spi.requestBodyCodec
import io.github.matthewjones372.pelican.spi.responseCodecs
import io.github.matthewjones372.pelican.spi.routeIndex
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/**
 * Interprets an [Api] as a handler for Gannet. Endpoints only: nothing here generates an OpenAPI
 * document.
 *
 * It passes on (returns false) wherever Pekko's route rejects, so a handler after it in a [then] chain is asked
 * next; [start] answers whatever every handler passed on with the 404 or 405 Pekko would.
 */
fun Api.toHandler(handlers: Handlers = Handlers.default): GannetHandler {
    require(endpoints.isNotEmpty()) { "This API has no endpoints." }

    // Once per endpoint rather than per request, so a broken codec is a startup failure.
    val codecs = endpoints.associateWith { it.endpoint.resolveCodecs(this.codecs) }
    val bound = endpoints.associateWith { endpoint -> handlerFor(endpoint) }

    val cors = corsPolicy()
    val answered = PelicanHandler(this, endpoints.routeIndex(), codecs, bound, cors, handlers)
    val files = pages ?: return answered
    // The loader that built the handler, as a carrier thread may carry no useful one.
    val loader = Thread.currentThread().contextClassLoader ?: Pages::class.java.classLoader
    return answered then PagesHandler(files, cors, loader).onlyFor(Method.GET)
}

/**
 * Routes on the carrier, which costs no hop for a 404, a refusal or an asynchronous handler. Whatever may block is
 * moved first, with everything that runs before it: a synchronous handler with its filters, an authenticator, and the
 * reading of a streamed body. A strict body is read where the endpoint runs; on the carrier that parks only the
 * connection's fiber.
 */
internal class PelicanHandler(
    private val api: Api,
    private val index: RouteIndex,
    private val codecs: Map<ServerEndpoint, EndpointCodecs>,
    private val handlers: Map<ServerEndpoint, (Params) -> CompletionStage<Any?>>,
    private val cors: CorsPolicy?,
    threads: Handlers,
) : GannetHandler {

    private val placement: Map<ServerEndpoint, Executor?> = api.endpoints.associateWith { it.placedBy(threads) }

    /** In declaration order, which is the order Pekko's per-method routes name them in a 405. */
    private val methods: List<Method> = api.endpoints.map { it.endpoint.method }.distinct()

    override fun handle(exchange: HttpExchange): Boolean {
        val answered = try {
            route(exchange)
        } catch (t: Throwable) {
            CompletableFuture.completedStage(errorResponse(t, api))
        } ?: return false
        // Detached either way: an answer ready now is written here, on the carrier, before this returns.
        exchange.detach()
        answered.whenComplete { reply, failure -> deliver(exchange, reply ?: errorResponse(failure, api)) }
        return true
    }

    /** [reply], or a 500 if writing it is refused, so a detached exchange is always answered. */
    private fun deliver(exchange: HttpExchange, reply: Reply) {
        try {
            reply.writeTo(exchange)
        } catch (t: Throwable) {
            try {
                errorResponse(t, api).writeTo(exchange)
            } catch (_: Throwable) {
                // Already answered: the reply that failed had started, and Gannet ends it.
            }
        }
    }

    /**
     * Pekko's routing, answered from the index: one route per declared verb, then the CORS preflight. A verb no
     * endpoint declares is passed on naming the ones that are; a declared verb at a path nothing under it
     * describes is passed on as a 404, even where another verb describes that path.
     */
    private fun route(exchange: HttpExchange): CompletionStage<Reply>? {
        val request = exchange.request
        val path = request.routedPath()
        if (path == null) {
            exchange.declinePath()
            return null
        }
        val method = methods.firstOrNull { it.name == request.method }
        if (method == null) {
            exchange.declineMethods(methods.map { it.name })
            return preflight(exchange, path)
        }
        val values = LinkedHashMap<ParamKey<*>, Any?>()

        val matched = try {
            index.match(method, path, values)
        } catch (t: Throwable) {
            // A capture that would not decode is that route's 400, so the route is looked up again to name it.
            val refused = index.routeFor(method, path)?.endpoint
            return CompletableFuture.completedStage(errorResponse(t, api, refused))
        }

        if (matched == null) {
            exchange.declinePath()
            return preflight(exchange, path)
        }
        val answered = invoke(matched, request, values)
        return if (cors == null) answered
        else answered.thenApply { it.withHeaders(cors.actualResponseHeaders(request.headers[CorsHeaders.ORIGIN])) }
    }

    /** Last, as on Pekko, so an endpoint declaring `OPTIONS` itself is tried first. */
    private fun preflight(exchange: HttpExchange, path: String): CompletionStage<Reply>? {
        if (cors == null) return null
        val request = exchange.request
        if (request.method != Method.OPTIONS.name) {
            exchange.declineMethods(listOf(Method.OPTIONS.name))
            return null
        }
        val decision = cors.preflight(
            origin = request.headers[CorsHeaders.ORIGIN],
            requestMethod = request.headers[CorsHeaders.REQUEST_METHOD],
            path = path,
        )
        return when (decision) {
            is CorsPreflight.NotPreflight -> {
                exchange.declinePath()
                null
            }

            is CorsPreflight.Refused ->
                CompletableFuture.completedStage(errorResponse(ApiException(403, "Forbidden", decision.reason), api))

            is CorsPreflight.Allowed ->
                CompletableFuture.completedStage(Strict(204, null, ByteArray(0), decision.headers))
        }
    }

    private fun invoke(
        se: ServerEndpoint,
        request: HttpRequest,
        values: MutableMap<ParamKey<*>, Any?>,
    ): CompletionStage<Reply> {
        val executor = placement.getValue(se) ?: return invokeHere(se, request, values)
        return CompletableFuture.supplyAsync({ invokeHere(se, request, values) }, executor).thenCompose { it }
    }

    private fun invokeHere(
        se: ServerEndpoint,
        request: HttpRequest,
        values: MutableMap<ParamKey<*>, Any?>,
    ): CompletionStage<Reply> {
        val ep = se.endpoint
        // Built before decoding, so a filter or a failing decode can still put a header on the way out.
        val credentials = request.credentials()
        val params = Params(values, request, ep, resumeFrom = request.resumePoint(ep), requestHeaders = credentials)

        val accept = request.headers.all(ACCEPT)

        // Who is calling first, then what they will take, then the inputs: Pekko's order, so a 401 tells nothing else.
        try {
            api.decodeCaller(ep, credentials, values)
            negotiate(ep, accept)
            decodePlainInputs(ep, request, values)
        } catch (t: Throwable) {
            return CompletableFuture.completedStage(errorResponse(t, api, ep).withHeaders(params.responseHeaders()))
        }

        val handled = try {
            readBody(ep, request, values, codecs.getValue(se))
            handlers.getValue(se)(params)
        } catch (t: Throwable) {
            CompletableFuture.failedStage(t)
        }
        return handled
            .thenApply { result -> buildResponse(ep.output, result, codecs.getValue(se), accept) }
            .exceptionally { t -> errorResponse(t, api, ep) }
            .thenApply { reply -> reply.withHeaders(params.responseHeaders()) }
    }

    /** The body, read the way its declaration says to. */
    private fun readBody(
        ep: Endpoint<*, *>,
        request: HttpRequest,
        values: MutableMap<ParamKey<*>, Any?>,
        codecs: EndpointCodecs,
    ) {
        when (val body = ep.bodyInput) {
            null -> Unit

            // Handed over unconsumed, and exempt from the limit as on Pekko: nothing here holds it whole.
            is RawBody -> values[body] = GannetByteStream(request.body)

            is MultipartBody -> readMultipart(request, api, body, values)

            is JsonBody<*>, is FormBody<*>, is NegotiatedBody<*> -> {
                val codec = checkNotNull(codecs.body) { "No codec was resolved for the body of $ep" }
                values[body] = readStrictBody(request, api, codec)
            }

            // Framed by core as the handler reads, so nothing is held but the frame being read.
            is NdjsonBody<*> -> {
                val frames = api.ndjsonFrames(ep, codecs.body, request.headers[CONTENT_TYPE])
                values[body] = GannetFrames<Any?>(request.body, frames)
            }
        }
    }
}

/**
 * Where [this] endpoint runs: on [threads]' executor when it may block before it answers, on a virtual thread when
 * it reads a streamed body and [threads] keeps handlers on the carrier, and on the carrier (null) otherwise.
 */
private fun ServerEndpoint.placedBy(threads: Handlers): Executor? {
    val streamed = endpoint.bodyInput.let { it is RawBody || it is NdjsonBody<*> || it is MultipartBody }
    val mayBlock = invoke is Synchronous || endpoint.caller != null || streamed
    return when {
        threads.executor != null && mayBlock -> threads.executor
        streamed -> OFF_CARRIER
        else -> null
    }
}

private const val ACCEPT = "Accept"

/** The `Last-Event-ID` of a reconnect, read only where core says the endpoint's stream can be resumed. */
private fun HttpRequest.resumePoint(ep: Endpoint<*, *>): String? =
    if (ep.resumable) headers[SseOutput.LAST_EVENT_ID] else null

/** Refuses an `Accept` taking nothing this endpoint sends, before any input is read; core decides, as on Pekko. */
private fun negotiate(ep: Endpoint<*, *>, accept: List<String>) {
    val produced = ep.output.produces
    if (produced.isEmpty() || accept.isEmpty()) return
    if (!acceptable(accept, produced)) throw NotAcceptable(produced)
}

/** The codecs one endpoint needs, resolved ahead of any request. */
internal class EndpointCodecs(
    val body: RequestBodyCodecs?,
    val payload: BodyCodec<Any?>?,
    /** One per declared response, keyed by identity: two can carry the same payload type under different statuses. */
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
