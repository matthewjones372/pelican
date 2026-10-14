package io.github.matthewjones372.pelican.jetty

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
import io.github.matthewjones372.pelican.spi.handlerFor
import io.github.matthewjones372.pelican.spi.ndjsonFrames
import io.github.matthewjones372.pelican.spi.requestBodyCodec
import io.github.matthewjones372.pelican.spi.responseCodecs
import io.github.matthewjones372.pelican.spi.routeIndex
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Interprets an [Api] as a Jetty handler. Endpoints only: nothing here generates an OpenAPI document.
 *
 * It passes on (returns false) wherever Pekko's route rejects, so a handler after it in a `Handler.Sequence` is
 * asked next. The 404 or 405 for a request nobody took comes from [Unanswered], which [start] installs and a
 * server built by hand should set as its `defaultHandler`. Such a server should also set its
 * `HttpConfiguration.uriCompliance` to [PELICAN_URI_COMPLIANCE], or Jetty answers a `%2F` in a path parameter
 * with its own 400 before this handler sees it.
 */
fun Api.toHandler(handlers: Handlers = Handlers.onVirtualThreads): Handler {
    require(endpoints.isNotEmpty()) { "This API has no endpoints." }

    // Once per endpoint rather than per request, so a broken codec is a startup failure.
    val codecs = endpoints.associateWith { it.endpoint.resolveCodecs(this.codecs) }
    val bound = endpoints.associateWith { endpoint -> handlerFor(endpoint).on(handlers, endpoint) }

    return PelicanHandler(this, endpoints.routeIndex(), codecs, bound, corsPolicy())
}

/** [handler] on [threads]' executor, when [endpoint] was bound synchronously and there is one to move to. */
private fun ((Params) -> CompletionStage<Any?>).on(
    threads: Handlers,
    endpoint: ServerEndpoint,
): (Params) -> CompletionStage<Any?> {
    val executor = threads.executor
    return if (executor == null || endpoint.invoke !is Synchronous) {
        this
    } else {
        { params -> CompletableFuture.supplyAsync({ this(params) }, executor).thenCompose { it } }
    }
}

internal class PelicanHandler(
    private val api: Api,
    private val index: RouteIndex,
    private val codecs: Map<ServerEndpoint, EndpointCodecs>,
    private val handlers: Map<ServerEndpoint, (Params) -> CompletionStage<Any?>>,
    private val cors: CorsPolicy?,
) : Handler.Abstract() {

    /** In declaration order, which is the order Pekko's per-method routes name them in a 405. */
    private val methods: List<Method> = api.endpoints.map { it.endpoint.method }.distinct()

    override fun handle(request: Request, response: Response, callback: Callback): Boolean {
        val answered = try {
            route(request)
        } catch (t: Throwable) {
            CompletableFuture.completedStage(errorResponse(t, api))
        } ?: return false
        answered.whenComplete { reply, failure -> (reply ?: errorResponse(failure, api)).writeTo(response, callback) }
        return true
    }

    /**
     * Pekko's routing, answered from the index: one route per declared verb, then the CORS preflight. A verb no
     * endpoint declares is passed on naming the ones that are; a declared verb at a path nothing under it
     * describes is passed on as a 404, even where another verb describes that path.
     */
    private fun route(request: Request): CompletionStage<Reply>? {
        val method = methods.firstOrNull { it.name == request.method }
        val path = request.encodedPathInContext()
        if (method == null) {
            request.declineMethods(methods.map { it.name })
            return preflight(request, path)
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
            request.declinePath()
            return preflight(request, path)
        }
        val answered = invoke(matched, request, values)
        return if (cors == null) answered
        else answered.thenApply { it.withHeaders(cors.actualResponseHeaders(request.headers.get(CorsHeaders.ORIGIN))) }
    }

    /** Last, as on Pekko, so an endpoint declaring `OPTIONS` itself is tried first. */
    private fun preflight(request: Request, path: String): CompletionStage<Reply>? {
        if (cors == null) return null
        if (request.method != Method.OPTIONS.name) {
            request.declineMethods(listOf(Method.OPTIONS.name))
            return null
        }
        val decision = cors.preflight(
            origin = request.headers.get(CorsHeaders.ORIGIN),
            requestMethod = request.headers.get(CorsHeaders.REQUEST_METHOD),
            path = path,
        )
        return when (decision) {
            is CorsPreflight.NotPreflight -> {
                request.declinePath()
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
        request: Request,
        values: MutableMap<ParamKey<*>, Any?>,
    ): CompletionStage<Reply> {
        val ep = se.endpoint
        // Built before decoding, so a filter or a failing decode can still put a header on the way out.
        val params = Params(values, request, ep, resumeFrom = request.resumePoint(ep))

        val accept = request.headers.getValuesList(HttpHeader.ACCEPT)

        // The caller check goes here, ahead of negotiation and the inputs as Pekko orders them.
        try {
            negotiate(ep, accept)
            decodePlainInputs(ep, request, values)
        } catch (t: Throwable) {
            return CompletableFuture.completedStage(errorResponse(t, api, ep).withHeaders(params.responseHeaders()))
        }

        return readBody(ep, request, values, codecs.getValue(se))
            .thenCompose { handlers.getValue(se)(params) }
            .thenApply { result -> buildResponse(ep.output, result, codecs.getValue(se), accept) }
            .exceptionally { t -> errorResponse(t, api, ep) }
            .thenApply { reply -> reply.withHeaders(params.responseHeaders()) }
    }

    /** The body, read the way its declaration says to. */
    private fun readBody(
        ep: Endpoint<*, *>,
        request: Request,
        values: MutableMap<ParamKey<*>, Any?>,
        codecs: EndpointCodecs,
    ): CompletionStage<Unit> = when (val body = ep.bodyInput) {
        null -> CompletableFuture.completedStage(Unit)

        // Handed over unconsumed, and exempt from the limit as on Pekko: nothing here holds it whole.
        is RawBody -> {
            values[body] = JettyByteStream(request)
            CompletableFuture.completedStage(Unit)
        }

        is MultipartBody -> readMultipart(request, api, body, values)

        is JsonBody<*>, is FormBody<*>, is NegotiatedBody<*> -> {
            val codec = checkNotNull(codecs.body) { "No codec was resolved for the body of $ep" }
            readStrictBody(request, api, codec).thenApply { values[body] = it }
        }

        // Framed by core as the handler reads, so nothing is held but the frame being read.
        is NdjsonBody<*> -> {
            val frames = api.ndjsonFrames(ep, codecs.body, request.headers.get(HttpHeader.CONTENT_TYPE))
            values[body] = JettyFrames<Any?>(request, frames)
            CompletableFuture.completedStage(Unit)
        }
    }
}

/** The `Last-Event-ID` of a reconnect, read only where core says the endpoint's stream can be resumed. */
private fun Request.resumePoint(ep: Endpoint<*, *>): String? =
    if (ep.resumable) headers.get(SseOutput.LAST_EVENT_ID) else null

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
