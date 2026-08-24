package example.filters

/*
 * The README's "Filters" section, verbatim, so the two things it claims are
 * checked by the compiler rather than by the reader: that a filter puts what it
 * worked out somewhere a handler can read, and that the handler reading it did
 * not have to ask again.
 *
 * `:example:runSecured` is the same idea taken to its conclusion — one filter
 * reading `endpoint.security` rather than a hand-written list of who may call
 * what. This file is the smaller version that fits on a page.
 */

import dev.pelican.Api
import dev.pelican.Filter
import dev.pelican.attribute
import dev.pelican.before
import dev.pelican.div
import dev.pelican.endpoint
import dev.pelican.headerParam
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.json
import dev.pelican.optional
import dev.pelican.pathParam
import dev.pelican.pekko.handledNow
import dev.pelican.tooManyRequests
import dev.pelican.unauthorized
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

fun reports() = Api(
    endpoints = listOf(
        // `this[caller]` is what the filter worked out. There is no second
        // check here, and no way for this handler to have skipped the first.
        getReport handledNow { (id, _) -> Report(id, "Q3", visibleTo = this[caller].subject) },
    ),
    codecs = JacksonCodecs,
    title = "Reports",
    version = "1.0.0",
    filters = listOf(requireToken, rateLimit),
)
