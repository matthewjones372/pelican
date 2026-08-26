package example.logging

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // startWithDocs, /openapi.json and Swagger UI
 *         implementation("io.github.matthewjones372:pelican-pekko-docs:1.0.0-RC1")
 *         // the interpreter; brings pelican-core and Pekko HTTP
 *         implementation("io.github.matthewjones372:pelican-pekko:1.0.0-RC1")
 *         // JacksonCodecs, and the schemas the document derives
 *         implementation("io.github.matthewjones372:pelican-jackson:1.0.0-RC1")
 *         // any SLF4J binding; Pelican logs through the API only
 *         runtimeOnly("ch.qos.logback:logback-classic:1.6.3")
 *     }
 */

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.RefusalObserver
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.afterStatus
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.attribute
import io.github.matthewjones372.pelican.before
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.err
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.metrics.otel.openTelemetry
import io.github.matthewjones372.pelican.metrics.otel.refusalCounter
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.onlyWhen
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs
import io.github.matthewjones372.pelican.pekko.handledNow
import io.github.matthewjones372.pelican.pekko.handledOrFail
import io.github.matthewjones372.pelican.text
import io.github.matthewjones372.pelican.unauthorized
import io.opentelemetry.api.OpenTelemetry
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/*
 * Logging a Pelican service: what the library already writes, and how to add an
 * access log of your own.
 *
 * `pelican-pekko` declares `org.slf4j:slf4j-api` and no binding, deliberately:
 * which logger a service runs is the service's decision, and a library that
 * picked one would put a second on the classpath of everyone who had already
 * chosen. With no binding at all SLF4J prints a warning and discards
 * everything, which is the "No SLF4J providers were found" line.
 *
 * `./gradlew :example:runLogging` — then curl the endpoints and watch stdout.
 */

// ============================================== 1. what Pelican logs already

/*
 * One thing, and it is the one a service must not lose: an exception nobody
 * declared. The caller gets an opaque reference and the log gets the stack
 * trace under `io.github.matthewjones372.pelican.pekko`, with the same
 * reference in both so they join up:
 *
 *     ERROR io.github.matthewjones372.pelican.pekko - Unhandled failure in GET /widgets/9 [ref f3ef2be]
 *     java.lang.IllegalStateException: the widget shelf fell over
 *
 * Everything else — which requests arrived, how long they took, what statuses
 * went out — is not logged, because a library that decided that for you would
 * be writing to your log at a level and a format you did not choose. The rest
 * of this file is how a service decides it.
 */

// ================================================= 2. an access log, as a filter

private val log: Logger = LoggerFactory.getLogger("example.logging.access")

/**
 * One line per request, at the level the status earns.
 *
 * `afterStatus` rather than `after`: it is told the status that is going out
 * rather than the value the handler returned, and — the reason it exists — it
 * also sees a request a *filter* refused. An access log that missed every 401
 * would be missing exactly the traffic it is read for.
 *
 * Nothing here is parsed out of a URL. The endpoint the request matched is on
 * `Params`, so the line carries the path *template* — `/widgets/{widgetId}`,
 * never `/widgets/9` — which is what makes a log searchable by route and a
 * metric one series per route rather than one per id.
 */
fun accessLog(): Filter = afterStatus { params, status, error ->
    val endpoint: Endpoint<*, *>? = params.endpoint
    val route = "${endpoint?.method} ${endpoint?.pathSpec?.template}"
    val operation = endpoint?.operationId ?: "unnamed"

    when {
        // Nobody described this one: the reference in the message is the same
        // one the caller was given, so a support ticket quoting it finds this line.
        error != null -> log.error("{} {} -> {}", route, operation, status, error)

        // A 5xx the service chose to answer with is still the service's problem.
        status >= SERVER_ERROR -> log.warn("{} {} -> {}", route, operation, status)

        // A refused request is a fact about the caller, not a fault: `info`, so
        // it survives a production level that drops `debug`.
        status >= CLIENT_ERROR -> log.info("{} {} -> {}", route, operation, status)

        else -> log.debug("{} {} -> {}", route, operation, status)
    }
}

/**
 * The refusals an access log structurally cannot see: a body over the limit, a
 * parameter that would not decode, an `Accept` nothing on offer satisfies.
 * They are answered before the filter chain is entered, so nothing in it runs.
 *
 * It is handed the reason, the status and the *template* of the route that
 * refused — never the request, the throwable or the detail, so there is
 * nothing here to leak into a log and nothing high-cardinality to tag with.
 */
fun refusalLog(): RefusalObserver = RefusalObserver { reason, status, template ->
    log.info("refused {} on {} -> {}", reason, template ?: "_unmatched", status)
}

// ============================== 3. a filter on some endpoints and not others

private val authorization = headerParam<String>("Authorization").optional()

private val caller = attribute<String>("caller")

/**
 * The token check itself. It knows nothing about which endpoints it guards —
 * that is the next declaration's job, and keeping the two apart is what lets
 * one rule be applied by two different policies.
 */
private val requireToken = before { p ->
    val presented = p[authorization]?.removePrefix("Bearer ")
    p[caller] = presented?.takeIf { it == "let-me-in" }
        ?: unauthorized("Present a bearer token")
}

/**
 * `onlyWhen` narrows a filter to the endpoints a predicate accepts, so the
 * health check is served without a token while everything else is not.
 *
 * The predicate is handed the `Endpoint` the request matched, so it can read
 * anything the description says — an operation id, a tag, a path template,
 * `deprecated`. Two spellings of the same intent:
 *
 *     requireToken.onlyWhen { it.operationId != "health" }        // by name
 *     requireToken.onlyWhen { it.security?.isEmpty() != true }    // by declaration
 *
 * The second is the one that scales: `noSecurity()` on the description is then
 * the single place the exemption is written, the padlock in Swagger UI agrees
 * with the filter by construction, and adding an endpoint cannot forget to
 * tell the filter about itself. `:example:runSecured` takes that to its
 * conclusion.
 */
private val tokenExceptHealth = requireToken.onlyWhen { endpoint ->
    endpoint.security?.isEmpty() != true
}

// ============================================ 4. tracing, from the same values

/**
 * A `SERVER` span per request, named from the description — `GET /widgets/{widgetId}`
 * rather than from the path that arrived — carrying `http.route`,
 * `http.request.method` and `http.response.status_code`, with its status set
 * from the outcome. `pelican-metrics-otel` is the module; the SDK is the
 * service's own, because which exporter a service runs is not the library's
 * decision.
 *
 * It composes with the access log rather than replacing it: a span is what a
 * trace UI joins across services, and the log line is what `grep` finds.
 */
fun tracing(sdk: OpenTelemetry): Filter = openTelemetry(sdk)

// ================================================== 3. a service that uses them

data class Widget(val id: Long, val name: String)

private val widgetId = pathParam<Long>("widgetId", description = "Which widget")

private val noSuchWidget = errorJson<ApiError>(404, "No widget with that id")

val getWidget = endpoint(widgetId) {
    get("widgets" / widgetId)
    operationId = "getWidget"
    summary = "Fetch one widget"
    json<Widget>() orFail noSuchWidget
}

/**
 * `noSecurity()` is the exemption, written on the description where a reader
 * of the document sees it too — and it is what `tokenExceptHealth` reads.
 */
val health = endpoint {
    get("health")
    operationId = "health"
    summary = "Is the service up"
    noSecurity()
    text()
}

private val shelf = mapOf(1L to Widget(1, "an anvil"), 2L to Widget(2, "a spring"))

val loggedRoutes: List<ServerEndpoint> = listOf(
    health handledNow { "ok" },

    getWidget handledOrFail { id ->
        // `/widgets/99` is the 404 the log writes at info; `/widgets/-1` is the
        // undeclared throw it writes at error, with the reference the caller got.
        require(id > 0) { "the widget shelf fell over" }
        shelf[id]?.let { ok(it) } ?: err(ApiError(404, "No widget $id"))
    },
)

fun loggedApi(sdk: OpenTelemetry? = null): Api = api(loggedRoutes, JacksonCodecs) {
    title = "Widgets"
    version = "1.0.0"

    // Outermost first, which is the order the request travels: the log wraps
    // everything so it records a refusal the token filter raised, and the token
    // filter runs before any handler.
    filter(accessLog())
    sdk?.let { filter(tracing(it)) }
    filter(tokenExceptHealth)
    onRefusal(refusalLog())
    // The refusals a span cannot see either, counted through the same SDK.
    sdk?.let { onRefusal(refusalCounter(it)) }

    // The library's own 500 line is written for whoever is debugging. This adds
    // the fields a log aggregator wants beside it; the caller still gets only
    // the reference.
    onError { reference, endpoint, error ->
        log.error("unhandled on {} [ref {}]", endpoint?.pathSpec?.template ?: "-", reference, error)
    }
}

private const val SERVER_ERROR = 500
private const val CLIENT_ERROR = 400
private const val DEFAULT_PORT = 8080

/** `./gradlew :example:runLogging` — then curl /widgets/1, /widgets/99 and /widgets/-1. */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toInt() ?: DEFAULT_PORT
    val server = loggedApi().startWithDocs(port = port, docs = docs { docsPath = "/api-docs" })
    println("Widgets on ${server.baseUrl} — docs at ${server.baseUrl}/api-docs")
}
