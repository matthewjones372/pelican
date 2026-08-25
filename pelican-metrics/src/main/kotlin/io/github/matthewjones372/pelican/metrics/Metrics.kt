package io.github.matthewjones372.pelican.metrics

import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.attempt
import io.github.matthewjones372.pelican.statusFor
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap

/**
 * A request count and a latency distribution for every endpoint the service
 * serves, dimensioned by what the descriptions already say.
 *
 * ```kotlin
 * api(routes, JacksonCodecs) { filter(metrics(registry)) }
 * ```
 *
 * That line is the whole of it. There is no per-route registration, no name to
 * pass and no tag list to keep in step with the router, because every dimension
 * below is read off the [Endpoint] the request matched:
 *
 * | Tag | Where it comes from |
 * |---|---|
 * | `method` | [Endpoint.method] |
 * | `path` | [Endpoint.pathSpec]'s template — `/orders/{orderId}`, never the id |
 * | `operation` | [Endpoint.operationId], or `unnamed` |
 * | `status` | what the interpreter is about to answer with |
 * | `deprecated` | [Endpoint.deprecated] |
 *
 * The `path` tag is the reason this belongs in the library rather than in a
 * handful of lines per service. A meter tagged with the request's *path* grows
 * one time series per order id, which is how a metrics bill and a monitoring
 * outage are made; a meter tagged with the request's *template* has one series
 * per route. Pelican knows the template because the route was built from it,
 * and so does not have to reverse-engineer it from the URL that arrived.
 *
 * `deprecated` is there because a description that announces an endpoint is
 * going away is only half the conversation. The other half is whether anybody
 * is still calling it, and that is a graph — `sum by (path) (rate(...{deprecated="true"}[1h]))`
 * — rather than a survey of downstream teams.
 *
 * ### What it records
 *
 * - `http.server.requests`, a counter: how many, split every way above.
 * - `http.server.request.duration`, a timer: how long, split the same way.
 *
 * A Micrometer [Timer] already publishes a count of its own, so the counter
 * looks redundant until the timer is aggregated: percentile histograms and
 * client-side percentiles are configured per meter and often turned off for
 * cheapness, and a service that has done so still wants the rate of 5xx. The
 * two names follow the OpenTelemetry semantic conventions rather than
 * Micrometer's own, so that the OpenTelemetry module this repository will grow
 * next can publish the same series under the same names.
 *
 * [prefix] is there for a service already publishing something under those
 * names — from a servlet container's own instrumentation, say — where two
 * unrelated sets of series under one name would be worse than a longer name.
 * Everything else Micrometer already answers better than a parameter here
 * could: percentiles, SLO boundaries, common tags and dropping a meter
 * altogether are all `MeterFilter`s on the registry.
 *
 * The status is resolved by core, in the one place that decides it, so what is
 * counted here is what the caller was sent. See `Endpoint.statusFor` for how,
 * and for the single case it cannot see.
 */
fun metrics(registry: MeterRegistry, prefix: String = DEFAULT_PREFIX): Filter {
    require(prefix.isNotBlank()) {
        "The meter prefix is what the two meter names are built from, so it has to be one: " +
            "metrics(registry, prefix = \"orders.http\"). Leave it out for `$DEFAULT_PREFIX`."
    }

    val meters = RequestMeters(registry, prefix)

    return Filter { params, next ->
        val endpoint = params.endpoint
        if (endpoint == null) {
            // Only a hand-built Params reaches here, and there is nothing to
            // tag it with. See `afterStatus` in core, which says the same.
            next(params)
        } else {
            val started = Timer.start(registry)
            // Through `attempt` rather than calling `next` directly: a filter
            // further in rejects by throwing, and a 401 that never reaches the
            // counter is the one every rate-of-refusals graph is drawn for.
            attempt(params, next).handle { result, error ->
                meters.record(endpoint, endpoint.statusFor(result, error), started)
                if (error != null) throw error
                result
            }
        }
    }
}

/** The names below are `http.server.requests` and `http.server.request.duration`. */
const val DEFAULT_PREFIX: String = "http.server"

/** What an endpoint that never named itself is tagged with. */
private const val UNNAMED = "unnamed"

/**
 * The meters, and the work of arriving at them done once rather than per
 * request.
 *
 * Micrometer will find an existing meter from a name and a tag list, so the
 * naive version of this class is a one-liner. What it would do on every single
 * request is build five [Tags] out of an endpoint that has not changed since
 * the route was built, and then hash them. Both maps below are bounded by the
 * service's own shape — one entry per endpoint, and one per endpoint and status
 * it has actually answered with — because the tag values come from the
 * description rather than from anything a caller sends.
 */
private class RequestMeters(private val registry: MeterRegistry, prefix: String) {

    private val requestsName = "$prefix.requests"
    private val durationName = "$prefix.request.duration"

    /** The four tags that are a property of the description alone. */
    private val described = ConcurrentHashMap<Endpoint<*, *>, Tags>()

    /** Those four plus the status, and the pair of meters carrying them. */
    private val meters = ConcurrentHashMap<Answered, Meters>()

    fun record(endpoint: Endpoint<*, *>, status: Int, started: Timer.Sample) {
        val pair = meters.computeIfAbsent(Answered(endpoint, status), ::build)
        pair.requests.increment()
        started.stop(pair.duration)
    }

    private fun build(answered: Answered): Meters {
        val tags = described
            .computeIfAbsent(answered.endpoint, ::describe)
            .and("status", answered.status.toString())

        return Meters(
            Counter.builder(requestsName)
                .description("Requests this service answered, by endpoint and status")
                .baseUnit("requests")
                .tags(tags)
                .register(registry),
            Timer.builder(durationName)
                .description("How long this service took to answer, by endpoint and status")
                .tags(tags)
                .register(registry),
        )
    }

    private fun describe(endpoint: Endpoint<*, *>): Tags = Tags.of(
        "method", endpoint.method.name,
        // The template, which is the low-cardinality form: one series per
        // route rather than one per value a caller happened to send.
        "path", endpoint.pathSpec.template,
        // A dashboard reads better against the name the description gave the
        // operation; an endpoint that never named itself falls back to a
        // constant rather than to something that would vary per request.
        "operation", endpoint.operationId ?: UNNAMED,
        "deprecated", endpoint.deprecated.toString(),
    )
}

/** One endpoint and one status it answered with: the key both meters hang off. */
private data class Answered(val endpoint: Endpoint<*, *>, val status: Int)

/** The two meters for one [Answered], looked up together because they are recorded together. */
private class Meters(val requests: Counter, val duration: Timer)
