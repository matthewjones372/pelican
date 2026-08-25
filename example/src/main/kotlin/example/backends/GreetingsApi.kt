package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.attribute
import io.github.matthewjones372.pelican.before
import io.github.matthewjones372.pelican.cors
import io.github.matthewjones372.pelican.forbidden
import io.github.matthewjones372.pelican.jackson.JacksonCodecs

/**
 * Everything about this service that is not a handler.
 *
 * The codecs, the title, the version — none of it is a property of the server,
 * so all three bindings build their `Api` through here rather than each
 * repeating it. That is also what makes the three documents comparable:
 * `AllBackendsTest` asserts the OpenAPI generated from each wiring is the same
 * string, which it can only be if nothing about the backend reached the
 * description.
 */
fun greetingsApi(
    routes: List<ServerEndpoint>,
    /**
     * Which descriptions [routes] must cover. Defaults to all of them, which is
     * what a real wiring wants; a test that binds a deliberate subset — see
     * `MethodMismatchTest` — passes an empty list to say so out loud.
     */
    covers: List<Endpoint<*, *>> = greetingEndpoints,

    /**
     * Filters to run *outside* this service's own, for a suite that wants to
     * watch every request rather than change one. Outside rather than inside
     * because anything observing the whole request — a metric, an access log —
     * belongs where it can also see the ones [gate] refuses: rejecting is
     * throwing, so a filter listed after [gate] never hears about a 403.
     */
    outerFilters: List<Filter> = emptyList(),
): Api = api(
    endpoints = routes,
    codecs = JacksonCodecs,
) {
    title = "Greetings"
    version = "1.0.0"
    description = "One set of endpoint descriptions, served by three different HTTP libraries."

    cors = cors("https://console.example.com")

    outerFilters.forEach { filter(it) }
    filter(stamping)
    filter(gate)

    maxBodyBytes = SMALL_ENOUGH_TO_PROVE

    // Every description in `greetingEndpoints` must be bound above. Leaving one
    // out is a startup failure rather than a documented 404.
    this.covers = covers

    webhooks = greetingWebhooks
}

/** What [stamping] worked out, for anything downstream that wants it. */
val correlationId = attribute<String>("correlationId")

/**
 * Puts a correlation id on every answer.
 */
val stamping: Filter = Filter { params, next ->
    val id = (if (traceId in params) params[traceId] else null)
        ?: ("gen-" + params.hashCode().toString(HEX))
    params[correlationId] = id
    params.setHeader(requestId, id)
    next(params)
}

/**
 * A filter that can say no.
 */
val gate: Filter = before { params ->
    if (traceId in params && params[traceId] == "blocked") {
        forbidden("This trace id is refused by the gate")
    }
}

private const val HEX = 16

/**
 * Small enough to prove in a test without shipping a megabyte. The default is
 * 8 MiB; a service that takes larger documents raises it, or takes the body as
 * a `rawBody()` stream and never holds it whole.
 */
private const val SMALL_ENOUGH_TO_PROVE = 4_096L
