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
import io.github.matthewjones372.pelican.Pages
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.RawBody
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.SseOutput
import io.github.matthewjones372.pelican.corsPolicy
import io.github.matthewjones372.pelican.spi.CorsHeaders
import io.github.matthewjones372.pelican.spi.EndpointPlan
import io.github.matthewjones372.pelican.spi.RequestBodyCodecs
import io.github.matthewjones372.pelican.spi.RouteIndex
import io.github.matthewjones372.pelican.spi.Slots
import io.github.matthewjones372.pelican.spi.acceptable
import io.github.matthewjones372.pelican.spi.decodeCaller
import io.github.matthewjones372.pelican.spi.handlerFor
import io.github.matthewjones372.pelican.spi.ndjsonFrames
import io.github.matthewjones372.pelican.spi.plan
import io.github.matthewjones372.pelican.spi.requestBodyCodec
import io.github.matthewjones372.pelican.spi.responseCodecs
import io.github.matthewjones372.pelican.spi.routeIndex
import io.github.matthewjones372.pelican.spi.succeeded
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.thread.Invocable.InvocationType
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
fun Api.toHandler(handlers: Handlers = Handlers.default): Handler {
    require(endpoints.isNotEmpty()) { "This API has no endpoints." }

    // Once per endpoint rather than per request, so a broken codec is a startup failure.
    val codecs = endpoints.associateWith { it.endpoint.resolveCodecs(this.codecs) }
    val plans = endpoints.associateWith { it.endpoint.plan() }
    val bound = endpoints.associateWith { endpoint -> handlerFor(endpoint).on(handlers, endpoint) }

    val cors = corsPolicy()
    val index = endpoints.routeIndex()
    val answered = PelicanHandler(this, index, plans, codecs, bound, cors, handlers)
    val files = pages
    val served = if (files == null) {
        answered
    } else {
        // The loader that built the handler, as a thread Jetty runs a request on may carry no useful one.
        val loader = Thread.currentThread().contextClassLoader ?: Pages::class.java.classLoader
        Handler.Sequence(answered, PagesHandler(files, cors, loader).onlyFor(Method.GET))
    }
    val policy = responseHeaders ?: return served
    val methods = endpoints.map { it.endpoint.method }
    return served.answeredUnder(policy, isDocs = false) { request ->
        val method = methods.firstOrNull { it.name == request.method }
        val path = request.routedPath()
        if (method == null || path == null) null else index.routeFor(method, path)?.endpoint
    }
}

/**
 * [handler] on [threads]' executor, when [endpoint] was bound synchronously and there is one to move to; on Jetty's
 * own pool instead when it is [ServerEndpoint.nonBlocking].
 */
private fun ((Params) -> CompletionStage<Any?>).on(
    threads: Handlers,
    endpoint: ServerEndpoint,
): (Params) -> CompletionStage<Any?> = when {
    threads.executor == null || endpoint.invoke !is Synchronous -> this
    endpoint.isNonBlocking -> { params -> onJettyPool(params) { this(params) } }
    else -> { params -> threads.mayBlock { this(params) } }
}

/**
 * [work] on Jetty's pool, where [Handlers.onDispatcher] has Jetty run it, unless already on a thread that may block.
 * Left on the selector it would hold one of the few threads every connection is read on (spec 0090).
 */
private fun <T> onJettyPool(params: Params, work: () -> CompletionStage<T>): CompletionStage<T> {
    if (mayBlockHere()) return work()
    val pool = (params.underlying as Request).connectionMetaData.connector.executor
    return CompletableFuture.supplyAsync(work, pool).thenCompose { it }
}

/**
 * [work] where these handlers may block: here under [Handlers.onDispatcher] or where [mayBlockHere], else hopped. A
 * hop to a virtual thread completes the stage inside [resumingHere], so a response written there costs no dispatch.
 */
private fun <T> Handlers.mayBlock(work: () -> CompletionStage<T>): CompletionStage<T> {
    val executor = executor
    return when {
        executor == null || (virtual && mayBlockHere()) -> work()

        virtual -> {
            val done = CompletableFuture<T>()
            executor.execute {
                resumingHere {
                    try {
                        work().whenComplete { value, failure ->
                            if (failure == null) done.complete(value) else done.completeExceptionally(failure)
                        }
                    } catch (t: Throwable) {
                        done.completeExceptionally(t)
                    }
                }
            }
            done
        }

        else -> CompletableFuture.supplyAsync(work, executor).thenCompose { it }
    }
}

/**
 * Whether [invoke] may block before the handler's own hop: an [io.github.matthewjones372.pelican.Authenticator]
 * is the caller's code, and an asynchronous handler may read a streamed body as it is called.
 */
private fun ServerEndpoint.blocksBeforeItsHandler(): Boolean {
    val streamed = endpoint.bodyInput.let { it is RawBody || it is NdjsonBody<*> }
    return endpoint.caller != null || (invoke !is Synchronous && streamed)
}

/**
 * Declared `NON_BLOCKING` unless [threads] is [Handlers.onDispatcher], so Jetty may call it on its selector without a
 * dispatch: routing, refusals and asynchronous handlers answer there, and whatever may block hops itself first.
 * `EITHER` would buy nothing: a `Server` combines it to `NON_BLOCKING`, and a selector calling directly does not set
 * `Invocable.isNonBlockingInvocation`, so the hop is decided by where Pelican knows it may block, never by that flag.
 */
internal class PelicanHandler(
    private val api: Api,
    private val index: RouteIndex,
    private val plans: Map<ServerEndpoint, EndpointPlan>,
    private val codecs: Map<ServerEndpoint, EndpointCodecs>,
    private val handlers: Map<ServerEndpoint, (Params) -> CompletionStage<Any?>>,
    private val cors: CorsPolicy?,
    private val threads: Handlers,
) : Handler.Abstract(if (threads.executor == null) InvocationType.BLOCKING else InvocationType.NON_BLOCKING) {

    private val blocking: Set<ServerEndpoint> = api.endpoints.filterTo(HashSet()) { it.blocksBeforeItsHandler() }

    /** In declaration order, which is the order Pekko's per-method routes name them in a 405. */
    private val methods: List<Method> = api.endpoints.map { it.endpoint.method }.distinct()

    override fun handle(request: Request, response: Response, callback: Callback): Boolean {
        // A tree that may block is dispatched by Jetty, never run on its selector, so a virtual thread here may block.
        val dispatched = threads.virtual && Thread.currentThread().isVirtual &&
            request.connectionMetaData.connector.server.invocationType == InvocationType.BLOCKING
        val answered = try {
            dispatchedHere(dispatched) { route(request) }
        } catch (t: Throwable) {
            CompletableFuture.completedFuture(errorResponse(t, api))
        } ?: return false
        val now = answered.succeeded()
        if (now != null) {
            now.resultNow().writeTo(response, callback.afterReadingRest(request))
        } else {
            answered.whenComplete { reply, failure ->
                (reply ?: errorResponse(failure, api)).writeTo(response, callback.afterReadingRest(request))
            }
        }
        return true
    }

    /**
     * Pekko's routing, answered from the index: one route per declared verb, then the CORS preflight. A verb no
     * endpoint declares is passed on naming the ones that are; a declared verb at a path nothing under it
     * describes is passed on as a 404, even where another verb describes that path.
     */
    private fun route(request: Request): CompletionStage<Reply>? {
        val path = request.routedPath()
        if (path == null) {
            request.declinePath()
            return null
        }
        val method = methods.firstOrNull { it.name == request.method }
        if (method == null) {
            request.declineMethods(methods.map { it.name })
            return preflight(request, path)
        }
        val matched = try {
            index.find(method, path)
        } catch (t: Throwable) {
            // A literal segment that would not decode is still the 400 of the route it was meant for.
            return CompletableFuture.completedFuture(errorResponse(t, api, index.routeFor(method, path)?.endpoint))
        }
        if (matched == null) {
            request.declinePath()
            return preflight(request, path)
        }

        val values = plans.getValue(matched).slots()
        try {
            index.decodeCaptures(matched, path, values)
        } catch (t: Throwable) {
            // A capture that would not decode is its own route's 400.
            return CompletableFuture.completedFuture(errorResponse(t, api, matched.endpoint))
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
                CompletableFuture.completedFuture(errorResponse(ApiException(403, "Forbidden", decision.reason), api))

            is CorsPreflight.Allowed ->
                CompletableFuture.completedFuture(Strict(204, null, ByteArray(0), decision.headers))
        }
    }

    private fun invoke(
        se: ServerEndpoint,
        request: Request,
        values: Slots,
    ): CompletionStage<Reply> =
        if (se in blocking) threads.mayBlock { invokeHere(se, request, values) } else invokeHere(se, request, values)

    private fun invokeHere(
        se: ServerEndpoint,
        request: Request,
        values: Slots,
    ): CompletionStage<Reply> {
        val ep = se.endpoint
        // Built before decoding, so a filter or a failing decode can still put a header on the way out.
        val view = JettyView(request)
        val params = Params(values, request, ep, resumeFrom = request.resumePoint(ep), requestHeaders = view)

        val accept = view.headers(HttpHeader.ACCEPT.asString())

        // Who is calling first, then what they will take, then the inputs: Pekko's order, so a 401 tells nothing else.
        try {
            api.decodeCaller(ep, view, values)
            negotiate(ep, accept)
            plans.getValue(se).decodePlainInputs(view, values)
        } catch (t: Throwable) {
            return CompletableFuture.completedFuture(errorResponse(t, api, ep).withHeaders(params.responseHeaders()))
        }

        val read = readBody(ep, request, values, codecs.getValue(se))
        val handled = if (read.succeeded() == null) {
            read.thenCompose { handlers.getValue(se)(params) }
        } else {
            try {
                handlers.getValue(se)(params)
            } catch (t: Throwable) {
                CompletableFuture.failedFuture(t)
            }
        }
        // A handler that has already answered, as every synchronous one has, is answered without a stage per step.
        val now = handled.succeeded()
        if (now != null) {
            val reply = try {
                buildResponse(ep.output, now.resultNow(), codecs.getValue(se), accept)
            } catch (t: Throwable) {
                errorResponse(t, api, ep)
            }
            return CompletableFuture.completedFuture(reply.withHeaders(params.responseHeaders()))
        }
        return handled
            .thenApply { result -> buildResponse(ep.output, result, codecs.getValue(se), accept) }
            .exceptionally { t -> errorResponse(t, api, ep) }
            .thenApply { reply -> reply.withHeaders(params.responseHeaders()) }
    }

    /** The body, read the way its declaration says to. */
    private fun readBody(
        ep: Endpoint<*, *>,
        request: Request,
        values: Slots,
        codecs: EndpointCodecs,
    ): CompletionStage<Unit> = when (val body = ep.bodyInput) {
        null -> CompletableFuture.completedFuture(Unit)

        // Handed over unconsumed, and exempt from the limit as on Pekko: nothing here holds it whole.
        is RawBody -> {
            values[body] = JettyByteStream(request)
            CompletableFuture.completedFuture(Unit)
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
            CompletableFuture.completedFuture(Unit)
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
    body = codecs.requestBodyCodec(this),
    payload = output.payloadType?.let { codecs.codec(it) },
    alternatives = codecs.responseCodecs(output),
)
