package example.filters

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // the interpreter; brings pelican-core and Jetty's server
 *         implementation("io.github.matthewjones372:pelican-jetty:1.0.0")
 *         // JacksonCodecs, and the schemas the document derives
 *         implementation("io.github.matthewjones372:pelican-jackson:1.0.0")
 *     }
 */

/**
 * The README's "Filters" section, verbatim, so the two things it claims are
 * checked by the compiler rather than by the reader: that a filter puts what it
 * worked out somewhere a handler can read, and that the handler reading it did
 * not have to ask again.
 */

import dev.surefold.pelican.Filter
import dev.surefold.pelican.api
import dev.surefold.pelican.attribute
import dev.surefold.pelican.before
import dev.surefold.pelican.div
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.headerParam
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.jetty.handledNow
import dev.surefold.pelican.optional
import dev.surefold.pelican.pathParam
import dev.surefold.pelican.tooManyRequests
import dev.surefold.pelican.unauthorized
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class Caller(val subject: String, val plan: String)

data class Report(val id: Long, val title: String, val visibleTo: String)

/** What the filter worked out, for the handlers to read. */
val caller = attribute<Caller>("caller")

private val tokens = mapOf("t-alice" to Caller("alice", plan = "free"))
private val seen = ConcurrentHashMap<String, AtomicInteger>()

/**
 * Rejecting is throwing. `unauthorized()` becomes the same 401 every other
 * refusal does, rendered by the interpreter rather than by this code.
 */
val requireToken: Filter = before { p ->
    val presented = p[authorization]?.removePrefix("Bearer ")
    p[caller] = tokens[presented] ?: unauthorized("Present a bearer token")
}

/**
 * Runs after [requireToken] because it is second in the list, so the caller it
 * reads is already there. Outermost first is the only ordering rule.
 */
val rateLimit: Filter = before { p ->
    val who = p[caller]
    if (who.plan == "free" && seen.computeIfAbsent(who.subject) { AtomicInteger() }.incrementAndGet() > 100) {
        tooManyRequests("100 requests an hour on the free plan", retryAfterSeconds = 3600)
    }
}

val authorization = headerParam<String>("Authorization", description = "Bearer token").optional()
val reportId = pathParam<Long>("reportId", description = "Which report")

val getReport = endpoint(reportId, authorization) {
    get("reports" / reportId)
    summary = "Fetch one report"
    json<Report>()
}

fun reports() = api(
    endpoints = listOf(
        // `this[caller]` is what the filter worked out. There is no second
        // check here, and no way for this handler to have skipped the first.
        getReport handledNow { (id, _) -> Report(id, "Q3", visibleTo = this[caller].subject) },
    ),
    codecs = JacksonCodecs,
) {
    title = "Reports"
    version = "1.0.0"
    filter(requireToken)
    filter(rateLimit)
}
