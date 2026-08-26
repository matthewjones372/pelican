package io.github.matthewjones372.pelican.metrics.otel

import io.github.matthewjones372.pelican.Attribute
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.RefusalObserver
import io.github.matthewjones372.pelican.RefusalReason
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.attempt
import io.github.matthewjones372.pelican.attribute
import io.github.matthewjones372.pelican.statusFor
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * A server span and the specified request-duration histogram for every request
 * the service answers, named and attributed from what the descriptions already
 * say.
 *
 * ```kotlin
 * api(routes, JacksonCodecs) { filter(openTelemetry(sdk)) }
 * ```
 *
 * That line is the whole of it, and it is the same line whichever interpreter
 * is underneath: this module reads an [Endpoint] and a [Filter], neither of
 * which knows whether Pekko, http4k or Ktor is serving the request.
 *
 * ### The span
 *
 * Named `GET /orders/{orderId}` — `{method} {http.route}`, which is what the
 * HTTP semantic conventions ask a server span to be called — and given
 * [SpanKind.SERVER]. The attributes on it, and where each comes from:
 *
 * | Attribute | Where it comes from |
 * |---|---|
 * | `http.request.method` | [Endpoint.method] |
 * | `http.route` | [Endpoint.pathSpec]'s template — `/orders/{orderId}`, never the id |
 * | `http.response.status_code` | what the interpreter is about to answer with |
 * | `error.type` | the status, as a string, when it is a 5xx |
 * | `pelican.operation_id` | [Endpoint.operationId], or `unnamed` |
 * | `pelican.deprecated` | [Endpoint.deprecated] |
 *
 * `http.route` is the reason a description-driven module can do this at all. A
 * general-purpose agent instrumenting Pekko or http4k sees a routing tree it
 * cannot name, so it either leaves the route off — which costs every
 * per-endpoint view a trace backend offers — or falls back to the request's own
 * path, one distinct span name per order id. Pelican has the template because
 * the route was built from it.
 *
 * The last two are in a namespace of their own because the conventions have no
 * key for either. `pelican.operation_id` is the name the description gave the
 * operation, which is what a dashboard reads better against than a path; and
 * `pelican.deprecated` is there for the same reason the Micrometer module tags
 * it — announcing that an endpoint is going away is only half the conversation,
 * and the other half is whether anybody is still calling it. Neither adds a
 * dimension: both are a function of the method and the route, which are already
 * attributes, so a metric split by them has exactly the series it had before.
 *
 * Span status follows the conventions rather than intuition: it is left unset
 * for a 4xx on a server span — a 404 is the endpoint doing its job, and marking
 * it an error makes every error rate a measure of how often callers ask for
 * things that are not there — and set to [StatusCode.ERROR] for a 5xx. A 5xx
 * that came from a throwable also records that throwable as a span event, which
 * is the one place the message is safe to put: `renderError` in core
 * deliberately keeps it out of the response body, so without this it goes
 * nowhere at all.
 *
 * ### The metric
 *
 * `http.server.request.duration`, a histogram in seconds with the bucket
 * boundaries the conventions recommend, attributed as the span is. It is the
 * specified instrument under the specified name, which is also why
 * `pelican-metrics` chose OpenTelemetry's names for its Micrometer meters: a
 * service that migrates from one to the other keeps its dashboards.
 *
 * ### Context propagation
 *
 * [incomingHeaders] is how an inbound `traceparent` continues the caller's
 * trace instead of starting a new one. It has to be supplied because a filter
 * cannot read a header nobody declared: [Params] carries the inputs the
 * endpoint declared and `Params.underlying` is the backend's own request type,
 * so reading an arbitrary header is the one thing this module cannot do
 * without knowing which server is underneath. One function per service closes
 * that gap — on Pekko it is `params.request.getHeader(key)` — and
 * `example/src/main/kotlin/example/telemetry/` has it written out.
 *
 * Left out, the parent is [Context.current] instead. That is deliberate rather
 * than a stub: a service running the OpenTelemetry Java agent already has an
 * extracted context current on the request thread, and parenting to it makes
 * this span a child of the agent's, adding the route the agent could not know
 * to a trace the agent already joined correctly.
 *
 * The span is made current only for the synchronous part of the call into the
 * rest of the chain, because that is the only part this module owns a thread
 * for — a handler returning a [java.util.concurrent.CompletionStage] completes
 * wherever its own executor decides. A handler that wants to nest a span reads
 * the context back from [otelContext] rather than relying on
 * [Context.current], and doing so is one line.
 *
 * ### What it cannot see
 *
 * The same blind spots the Micrometer module has, for the same reasons, and they
 * are worth knowing before an error rate is read off any of this:
 *
 * - A request answered before the chain is entered — a path parameter that will
 *   not decode, a body over `Api.maxBodyBytes`, a `Content-Type` nothing
 *   declared — reaches no filter, so it produces no span and no measurement
 *   here. The 4xx rate on this histogram is the rate of *handled* 4xx.
 *   [refusalCounter] is where those requests are counted instead.
 * - A response that fails while it is being written becomes a 500 after the
 *   chain has already unwound, so the span carries the status the handler asked
 *   for rather than the one the caller received. See `Endpoint.statusFor`,
 *   which says the same where the status is decided.
 *
 * The attributes the conventions mark required for a server span and this
 * module does not set — `url.path`, `url.scheme`, and the recommended
 * `server.address`, `client.address` and `network.*` — are left off rather than
 * guessed. Every one of them is a property of the socket rather than of the
 * description, and a filter that works identically on three interpreters is
 * looking at the description. A service that wants them either supplies them
 * from a filter of its own that knows its backend, or runs the agent, whose
 * server span is the one carrying them.
 *
 * @param telemetry where the spans and measurements go. `OpenTelemetry.noop()`
 *   is a valid answer and costs almost nothing, which is what makes this safe
 *   to leave wired in a service that has not decided on a backend yet.
 * @param incomingHeaders how to read a request header off this backend's own
 *   request, for continuing an inbound trace. Null does not extract.
 * @param scopeName the instrumentation scope the spans and the histogram are
 *   recorded under. Worth changing only to tell two Pelican `Api`s in one
 *   process apart.
 */
fun openTelemetry(
    telemetry: OpenTelemetry,
    incomingHeaders: TextMapGetter<Params>? = null,
    scopeName: String = INSTRUMENTATION_SCOPE,
): Filter {
    require(scopeName.isNotBlank()) {
        "The scope name is what a backend attributes these spans to, so it has to be one: " +
            "openTelemetry(sdk, scopeName = \"orders\"). Leave it out for `$INSTRUMENTATION_SCOPE`."
    }

    val recorder = Recorder(telemetry, scopeName)
    val propagator = telemetry.propagators.textMapPropagator

    return Filter { params, next ->
        val endpoint = params.endpoint
        if (endpoint == null) {
            // Only a hand-built Params reaches here, and there is nothing to
            // name a span after. See `afterStatus` in core, which says the same.
            next(params)
        } else {
            val described = recorder.describe(endpoint)
            val parent =
                if (incomingHeaders == null) Context.current()
                else propagator.extract(Context.current(), params, incomingHeaders)

            val span = recorder.begin(described, parent)
            params[otelContext] = parent.with(span)

            val startedAt = System.nanoTime()
            // Through `attempt` rather than calling `next` directly: a filter
            // further in rejects by throwing, and a 401 that never reached a
            // trace is the request somebody is going to go looking for.
            val rest = span.makeCurrent().use { attempt(params, next) }
            rest.handle { result, error ->
                val failure = error?.unwrapCompletion()
                val elapsed = System.nanoTime() - startedAt
                recorder.end(span, described, endpoint.statusFor(result, failure), failure, elapsed)
                if (error != null) throw error
                result
            }
        }
    }
}

/**
 * The requests [openTelemetry] is structurally unable to see: those refused
 * before the filter chain was entered.
 *
 * ```kotlin
 * api(routes, JacksonCodecs) {
 *     filter(openTelemetry(sdk))
 *     onRefusal(refusalCounter(sdk))
 * }
 * ```
 *
 * The same counter `pelican-metrics` publishes to Micrometer, under the same
 * name, so a service moving between the two keeps its dashboards:
 * `http.server.refusals`, attributed by [RefusalReason.label], the status the
 * caller was sent, and `http.route` — the refusing route's template, or
 * [UNMATCHED] where nothing matched.
 *
 * No span. A refusal is answered before any handler runs and has no work to
 * describe; a span per rejected probe would cost a trace backend a great deal
 * to say the same thing this counter says. It is also why the attributes stop
 * at three: an unmatched request carries a caller's arbitrary path, and
 * recording that would grow one series per probe.
 */
fun refusalCounter(telemetry: OpenTelemetry, scopeName: String = INSTRUMENTATION_SCOPE): RefusalObserver {
    require(scopeName.isNotBlank()) {
        "The scope name is what a backend attributes this counter to, so it has to be one: " +
            "refusalCounter(sdk, scopeName = \"orders\"). Leave it out for `$INSTRUMENTATION_SCOPE`."
    }

    val refusals = telemetry.getMeter(scopeName)
        .counterBuilder(REFUSALS_METRIC)
        .setDescription("Requests refused before the filter chain, by reason and route.")
        .setUnit("{request}")
        .build()

    // One attribute set per distinct refusal rather than one per refused
    // request, as the span attributes are cached per endpoint. Bounded by the
    // closed set of reasons, core's own status table and the described routes.
    val attributes = ConcurrentHashMap<Refused, Attributes>()

    return RefusalObserver { reason, status, pathTemplate ->
        val key = Refused(reason, status, pathTemplate ?: UNMATCHED)
        refusals.add(1, attributes.computeIfAbsent(key, ::attributesFor))
    }
}

/** One reason, one status and one route: the key the attribute set hangs off. */
private data class Refused(val reason: RefusalReason, val status: Int, val route: String)

private fun attributesFor(refused: Refused): Attributes = Attributes.builder()
    .put(PELICAN_REFUSAL_REASON, refused.reason.label)
    .put(HTTP_RESPONSE_STATUS_CODE, refused.status.toLong())
    .put(HTTP_ROUTE, refused.route)
    .build()

/**
 * The context this request's span sits in, for a handler or a later filter that
 * wants to nest work under it.
 *
 * ```kotlin
 * val child = tracer.spanBuilder("charge").setParent(params[otelContext]).startSpan()
 * ```
 *
 * Reading it rather than [Context.current] is the reliable form, because the
 * span is only current for as long as this module holds the request thread —
 * see the note on propagation above.
 */
val otelContext: Attribute<Context> = attribute("otel.context")

/** What the spans and the histogram are attributed to unless a caller says otherwise. */
const val INSTRUMENTATION_SCOPE: String = "io.github.matthewjones372.pelican"

/** What an endpoint that never named itself is attributed with. */
private const val UNNAMED = "unnamed"

/** What `http.route` says on a refusal where the request matched no route at all. */
const val UNMATCHED: String = "_unmatched"

/** The status from which the conventions call a server span's outcome an error. */
private const val LOWEST_SERVER_ERROR = 500

private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * The attribute keys, spelled as the current HTTP semantic conventions spell
 * them.
 *
 * Written out here rather than taken from `io.opentelemetry.semconv`, which is
 * the artifact that generates them. That artifact would be a third dependency
 * on every consumer's classpath, versioned separately from the API, carrying
 * constants for the whole registry — several hundred keys — so that this module
 * can use four of them. Four strings and the version of the specification they
 * were read from is the smaller claim, and it is checkable in the same way.
 *
 * These are the stable names introduced when the HTTP conventions were declared
 * stable, not the pre-1.0 ones: `http.request.method` rather than `http.method`,
 * `http.response.status_code` rather than `http.status_code`. Anything still
 * emitting the old spellings is emitting deprecated attributes.
 */
private val HTTP_REQUEST_METHOD = AttributeKey.stringKey("http.request.method")
private val HTTP_ROUTE = AttributeKey.stringKey("http.route")
private val HTTP_RESPONSE_STATUS_CODE = AttributeKey.longKey("http.response.status_code")
private val ERROR_TYPE = AttributeKey.stringKey("error.type")

/** Pelican's own three, in a namespace of their own because the conventions have no key for any. */
private val PELICAN_OPERATION_ID = AttributeKey.stringKey("pelican.operation_id")
private val PELICAN_DEPRECATED = AttributeKey.booleanKey("pelican.deprecated")
private val PELICAN_REFUSAL_REASON = AttributeKey.stringKey("pelican.refusal_reason")

/** The name the conventions give the instrument, and the buckets they recommend for it. */
private const val DURATION_METRIC = "http.server.request.duration"

/** Not a name the conventions have. It is `pelican-metrics`'s, so the two publish one series. */
private const val REFUSALS_METRIC = "http.server.refusals"

private val DURATION_BUCKETS =
    listOf(0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0)

/**
 * The tracer, the histogram, and the work of arriving at a span name and an
 * attribute set done once per endpoint rather than once per request.
 *
 * The naive version builds an [Attributes] on every request out of an endpoint
 * that has not changed since the route was built. Both caches below are bounded
 * by the service's own shape — one entry per endpoint, and one per endpoint and
 * status it has actually answered with — because every value in them comes from
 * the description rather than from anything a caller sends.
 */
private class Recorder(telemetry: OpenTelemetry, scopeName: String) {

    private val tracer: Tracer = telemetry.getTracer(scopeName)

    private val duration: DoubleHistogram = telemetry.getMeter(scopeName)
        .histogramBuilder(DURATION_METRIC)
        .setDescription("Duration of HTTP server requests.")
        .setUnit("s")
        .setExplicitBucketBoundariesAdvice(DURATION_BUCKETS)
        .build()

    private val described = ConcurrentHashMap<Endpoint<*, *>, Described>()

    fun describe(endpoint: Endpoint<*, *>): Described = described.computeIfAbsent(endpoint, ::readOff)

    fun begin(described: Described, parent: Context): Span = tracer
        .spanBuilder(described.spanName)
        .setSpanKind(SpanKind.SERVER)
        .setParent(parent)
        .setAllAttributes(described.attributes)
        .startSpan()

    fun end(span: Span, described: Described, status: Int, failure: Throwable?, elapsedNanos: Long) {
        span.setAttribute(HTTP_RESPONSE_STATUS_CODE, status.toLong())
        if (status >= LOWEST_SERVER_ERROR) {
            // The conventions: unset for 4xx on a server span, Error for 5xx.
            span.setStatus(StatusCode.ERROR)
            span.setAttribute(ERROR_TYPE, status.toString())
            // The status rather than the exception's class name, which the
            // conventions also allow: the class name is on the exception event
            // a line below, where a reader can have the stack trace with it,
            // and the metric wants an attribute that adds no series.
            if (failure != null) span.recordException(failure)
        }
        span.end()

        duration.record(elapsedNanos / NANOS_PER_SECOND, described.measuredAs(status))
    }

    private fun readOff(endpoint: Endpoint<*, *>): Described {
        val route = endpoint.pathSpec.template
        return Described(
            spanName = "${endpoint.method.name} $route",
            attributes = Attributes.builder()
                .put(HTTP_REQUEST_METHOD, endpoint.method.name)
                .put(HTTP_ROUTE, route)
                .put(PELICAN_OPERATION_ID, endpoint.operationId ?: UNNAMED)
                .put(PELICAN_DEPRECATED, endpoint.deprecated)
                .build(),
        )
    }
}

/**
 * One endpoint, read once: what its spans are called, what they carry, and the
 * attribute set each status it has answered with is measured under.
 */
private class Described(val spanName: String, val attributes: Attributes) {

    private val perStatus = ConcurrentHashMap<Int, Attributes>()

    fun measuredAs(status: Int): Attributes = perStatus.computeIfAbsent(status, ::withStatus)

    private fun withStatus(status: Int): Attributes {
        val builder = attributes.toBuilder().put(HTTP_RESPONSE_STATUS_CODE, status.toLong())
        if (status >= LOWEST_SERVER_ERROR) builder.put(ERROR_TYPE, status.toString())
        return builder.build()
    }
}

/**
 * The throwable itself rather than the stage's wrapper.
 *
 * `Endpoint.statusFor` unwraps for itself, so this is here for the exception
 * event: recording a `CompletionException` would put this module's own plumbing
 * at the top of the stack trace a reader opened the trace to see.
 */
private fun Throwable.unwrapCompletion(): Throwable =
    if (this is CompletionException) cause?.unwrapCompletion() ?: this else this
