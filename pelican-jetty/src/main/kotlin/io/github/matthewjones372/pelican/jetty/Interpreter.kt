package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.FormBody
import io.github.matthewjones372.pelican.JsonBody
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.MultipartBody
import io.github.matthewjones372.pelican.NdjsonBody
import io.github.matthewjones372.pelican.NegotiatedBody
import io.github.matthewjones372.pelican.Output
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.RawBody
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.spi.RequestBodyCodecs
import io.github.matthewjones372.pelican.spi.RouteIndex
import io.github.matthewjones372.pelican.spi.handlerFor
import io.github.matthewjones372.pelican.spi.requestBodyCodec
import io.github.matthewjones372.pelican.spi.responseCodecs
import io.github.matthewjones372.pelican.spi.routeIndex
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * Interprets an [Api] as a Jetty handler that answers every request it is given, a path it does not describe
 * included. Endpoints only: nothing here generates an OpenAPI document.
 *
 * A server built by hand should set its `HttpConfiguration.uriCompliance` to [PELICAN_URI_COMPLIANCE], or Jetty
 * answers a `%2F` in a path parameter with its own 400 before this handler sees it.
 */
fun Api.toHandler(handlers: Handlers = Handlers.onVirtualThreads): Handler {
    require(endpoints.isNotEmpty()) { "This API has no endpoints." }

    // Once per endpoint rather than per request, so a broken codec is a startup failure.
    val codecs = endpoints.associateWith { it.endpoint.resolveCodecs(this.codecs) }
    val bound = endpoints.associateWith { endpoint -> handlerFor(endpoint).on(handlers, endpoint) }

    return PelicanHandler(this, endpoints.routeIndex(), codecs, bound)
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
) : Handler.Abstract() {

    /** In declaration order, which is the order Pekko's per-method routes name them in a 405. */
    private val methods: List<Method> = api.endpoints.map { it.endpoint.method }.distinct()

    private val notAllowed = methodNotAllowed(methods)

    override fun handle(request: Request, response: Response, callback: Callback): Boolean {
        val answered = try {
            route(request)
        } catch (t: Throwable) {
            CompletableFuture.completedStage(errorResponse(t, api))
        }
        answered.whenComplete { reply, failure -> (reply ?: errorResponse(failure, api)).writeTo(response, callback) }
        return true
    }

    /**
     * Pekko's routing, answered from the index: a verb no endpoint declares is a 405 whatever the path, and a
     * declared verb at a path nothing under it describes is a 404, even where another verb describes that path.
     */
    private fun route(request: Request): CompletionStage<Reply> {
        val method = methods.firstOrNull { it.name == request.method }
            ?: return CompletableFuture.completedStage(notAllowed)
        val path = request.encodedPathInContext()
        val values = LinkedHashMap<ParamKey<*>, Any?>()

        val matched = try {
            index.match(method, path, values)
        } catch (t: Throwable) {
            // A capture that would not decode is that route's 400, so the route is looked up again to name it.
            val refused = index.routeFor(method, path)?.endpoint
            return CompletableFuture.completedStage(errorResponse(t, api, refused))
        }

        return if (matched == null) {
            CompletableFuture.completedStage(NOT_FOUND)
        } else {
            invoke(matched, request, values)
        }
    }

    private fun invoke(
        se: ServerEndpoint,
        request: Request,
        values: MutableMap<ParamKey<*>, Any?>,
    ): CompletionStage<Reply> {
        val ep = se.endpoint
        // Built before decoding, so a filter or a failing decode can still put a header on the way out.
        val params = Params(values, request, ep)

        // The caller check, then negotiation, go here, ahead of the inputs as Pekko orders them.
        try {
            decodePlainInputs(ep, request, values)
        } catch (t: Throwable) {
            return CompletableFuture.completedStage(errorResponse(t, api, ep).withHeaders(params.responseHeaders()))
        }

        return readBody(ep, request, values, codecs.getValue(se))
            .thenCompose { handlers.getValue(se)(params) }
            .thenApply { result -> buildResponse(ep.output, result, codecs.getValue(se)) }
            .exceptionally { t -> errorResponse(t, api, ep) }
            .thenApply { reply -> reply.withHeaders(params.responseHeaders()) }
    }

    /** The body, read the way its declaration says to. NDJSON is not yet read on Jetty. */
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

        is NdjsonBody<*> ->
            CompletableFuture.failedStage(UnsupportedOperationException("Not yet read on Jetty: $body"))
    }
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
