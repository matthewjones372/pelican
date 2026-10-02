package io.github.matthewjones372.pelican.metrics.otel

import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.ClientTransport
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.context.propagation.TextMapSetter
import java.net.URI
import java.util.concurrent.CompletionStage

/**
 * A [ClientTransport] that traces every call it sends: a client span, the
 * specified `http.client.request.duration` histogram, and the span's context
 * written onto the request as `traceparent` and `tracestate`, so the server
 * that answers continues the caller's trace rather than starting one.
 *
 * ```kotlin
 * val client = ScreeningClient(url, JacksonCodecs, PekkoHttpTransport().traced(sdk))
 * ```
 *
 * The other half of [openTelemetry]: that filter continues an inbound trace,
 * and this is what sends one. A decorator, as [retrying][io.github.matthewjones372.pelican.retrying]
 * is, so it is chosen once where the transport is built and works for every
 * client generated against the interface, before this existed or after.
 *
 * ### The span
 *
 * A child of [Context.current] on the thread that calls `send`, which is the
 * caller's. Named by the method alone — `GET` — because the route template a
 * server span is named after is the description's, and a transport sees only
 * the URL it was handed; the conventions name a client span this way when the
 * route is unknown. Attributed with `http.request.method`, `url.full`,
 * `server.address`, `server.port` and, once answered,
 * `http.response.status_code`.
 *
 * Status follows the conventions for a client, which differ from a server's: a
 * 4xx is an error here, since the call did not get what it asked for, as is a
 * 5xx and a call that never got an answer. The answer is passed through
 * untouched either way: tracing a call never changes what it returned.
 *
 * Wrapped outside [retrying][io.github.matthewjones372.pelican.retrying], one
 * span covers every attempt; inside, each attempt is a span of its own.
 *
 * @param telemetry where the spans and measurements go, and whose propagators
 *   write the context onto the request.
 * @param scopeName the instrumentation scope they are recorded under.
 */
fun ClientTransport.traced(telemetry: OpenTelemetry, scopeName: String = INSTRUMENTATION_SCOPE): ClientTransport {
    require(scopeName.isNotBlank()) {
        "The scope name is what a backend attributes these spans to, so it has to be one: " +
            "traced(sdk, scopeName = \"orders\"). Leave it out for `$INSTRUMENTATION_SCOPE`."
    }
    return TracedTransport(this, telemetry, scopeName)
}

private class TracedTransport(
    private val delegate: ClientTransport,
    telemetry: OpenTelemetry,
    scopeName: String,
) : ClientTransport {
    private val tracer: Tracer = telemetry.getTracer(scopeName)
    private val propagator: TextMapPropagator = telemetry.propagators.textMapPropagator
    private val duration: DoubleHistogram = telemetry.getMeter(scopeName)
        .histogramBuilder(CLIENT_DURATION_METRIC)
        .setDescription("Duration of HTTP client requests.")
        .setUnit("s")
        .setExplicitBucketBoundariesAdvice(DURATION_BUCKETS)
        .build()

    override fun send(request: ClientRequest): CompletionStage<ClientResponse> {
        val method = request.method.name
        val target = URI.create(request.url)
        val measured = Attributes.builder().put(HTTP_REQUEST_METHOD, method)
            .apply { target.host?.let { put(SERVER_ADDRESS, it) } }
            .put(SERVER_PORT, port(target).toLong())
        val span = tracer.spanBuilder(method)
            .setSpanKind(SpanKind.CLIENT)
            .setParent(Context.current())
            .setAllAttributes(measured.build())
            .setAttribute(URL_FULL, request.url)
            .startSpan()

        val headers = mutableListOf<Pair<String, String>>()
        propagator.inject(Context.current().with(span), headers, SETTER)
        val traced = headers.fold(request) { carried, (name, value) -> carried.withHeader(name, value) }

        val startedAt = System.nanoTime()
        // A transport that throws rather than failing its stage is a bug the
        // caller hears about as it would have; the span is still ended, so it
        // is not left open for the life of the process.
        var handedOver = false
        val sent = try {
            delegate.send(traced).also { handedOver = true }
        } finally {
            if (!handedOver) end(span, measured, null, null, startedAt)
        }
        // whenComplete rather than handle: the caller gets exactly what the
        // delegate answered, failure and all.
        return sent.whenComplete { response, error ->
            end(span, measured, response?.status, error?.unwrapCompletion(), startedAt)
        }
    }

    private fun end(span: Span, measured: AttributesBuilder, status: Int?, failure: Throwable?, startedAt: Long) {
        val elapsed = (System.nanoTime() - startedAt) / NANOS_PER_SECOND
        when {
            failure != null -> {
                span.recordException(failure)
                span.setStatus(StatusCode.ERROR)
                span.setAttribute(ERROR_TYPE, failure.javaClass.name)
                measured.put(ERROR_TYPE, failure.javaClass.name)
            }

            status != null -> {
                span.setAttribute(HTTP_RESPONSE_STATUS_CODE, status.toLong())
                measured.put(HTTP_RESPONSE_STATUS_CODE, status.toLong())
                if (status >= LOWEST_CLIENT_ERROR) {
                    span.setStatus(StatusCode.ERROR)
                    span.setAttribute(ERROR_TYPE, status.toString())
                    measured.put(ERROR_TYPE, status.toString())
                }
            }
        }
        duration.record(elapsed, measured.build())
        span.end()
    }
}

/** The port a URL names, or its scheme's. */
private fun port(target: URI): Int = when {
    target.port != -1 -> target.port
    target.scheme.equals("https", ignoreCase = true) -> HTTPS_PORT
    else -> HTTP_PORT
}

/** Writes each header the propagator asks for into a list, in order. */
private val SETTER = TextMapSetter<MutableList<Pair<String, String>>> { carrier, key, value ->
    carrier?.add(key to value)
}

private const val CLIENT_DURATION_METRIC = "http.client.request.duration"
private const val LOWEST_CLIENT_ERROR = 400
private const val HTTP_PORT = 80
private const val HTTPS_PORT = 443
private val URL_FULL = AttributeKey.stringKey("url.full")
private val SERVER_ADDRESS = AttributeKey.stringKey("server.address")
private val SERVER_PORT = AttributeKey.longKey("server.port")
