package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.ServerEndpoint
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
): Api = Api(
    endpoints = routes,
    codecs = JacksonCodecs,
    title = "Greetings",
    version = "1.0.0",
    description = "One set of endpoint descriptions, served by three different HTTP libraries.",

    // Cross-origin access for one named origin. What that origin is *allowed*
    // to do is not written here: the methods and the request headers a
    // preflight answers with are read off the endpoints above, so `echo`
    // gaining a header is a browser gaining permission to send it, with
    // nothing else to change. `CorsTest` asserts all three backends say the
    // same thing.
    cors = cors("https://console.example.com"),

    // Two filters, in the order a request meets them. Written once, here, and
    // run by all three interpreters — a filter is a description-level thing
    // like everything else, so `AllBackendsTest` can hold the three to the
    // same answers about them.
    filters = listOf(stamping, gate),

    // Small enough to prove in a test without shipping a megabyte. The default
    // is 8 MiB; a service that takes larger documents raises it, or takes the
    // body as a `rawBody()` stream and never holds it whole.
    maxBodyBytes = 4_096,

    // Every description in `greetingEndpoints` must be bound above. Leaving one
    // out is a startup failure rather than a documented 404.
    covers = covers,

    // The call this service sends. Nothing above binds it and nothing below
    // routes it: the three interpreters build their routes from `endpoints`,
    // and a webhook goes to a URL a subscriber registered rather than to a path
    // here. It reaches the document and the generated sender, and stops there.
    webhooks = greetingWebhooks,
)

/** What [stamping] worked out, for anything downstream that wants it. */
val correlationId = attribute<String>("correlationId")

/**
 * Puts a correlation id on every answer.
 *
 * The interesting part is that this is *one* filter and there are three
 * endpoints, none of whose handlers mention a header. `setHeader` takes the
 * same [requestId] value the endpoints declared with `emits(...)`, so a header
 * cannot be stamped here and missing from the document — passing one no
 * endpoint declared throws instead.
 */
val stamping: Filter = Filter { params, next ->
    // `traceId` is declared on `echo` and nowhere else, and a filter runs for
    // every endpoint — so this asks whether the key is there rather than
    // assuming it. Reading an undeclared key throws, deliberately: on a handler
    // that is a wiring mistake, and a filter is the one place it is not.
    val id = (if (traceId in params) params[traceId] else null)
        ?: ("gen-" + params.hashCode().toString(HEX))
    params[correlationId] = id
    params.setHeader(requestId, id)
    next(params)
}

/**
 * A filter that can say no.
 *
 * Rejecting is throwing, so the 403 is rendered by the same code that renders
 * every other declared failure, on every backend — and `next` is never called,
 * which is what "short-circuit" has to mean.
 */
val gate: Filter = before { params ->
    if (traceId in params && params[traceId] == "blocked") {
        forbidden("This trace id is refused by the gate")
    }
}

private const val HEX = 16
