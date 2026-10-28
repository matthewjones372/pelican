package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.newReference
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/** A budget of [permits] requests every [per], shared by every endpoint that names it. Made with [rateLimit]. */
class RateLimit internal constructor(val name: String, val permits: Int, val per: Duration) {
    /** The time one permit takes to come back. */
    internal val interval: Duration get() = per / permits

    override fun toString() = "$permits requests every $per ($name)"
}

/** [permits] requests every [per] under [name]; endpoints naming the same limit spend from one budget. */
fun rateLimit(name: String, permits: Int, per: Duration): RateLimit {
    require(name.isNotBlank()) { "A rate limit is named, so endpoints sharing a budget can say so." }
    require(permits > 0 && per.isPositive()) { "A rate limit is a positive number of requests over a positive time." }
    return RateLimit(name, permits, per)
}

/** Whose budget a request spends: the limit's name, and the caller, tenant and endpoint's own key where it has them. */
data class RateLimitKey(val limit: String, val subject: String?, val tenant: String?, val own: String?)

/** What a [RateLimiter] says to one request. */
sealed interface RateDecision {
    /** It may go ahead, and has spent a permit. */
    data object Allowed : RateDecision

    /** It may not; a permit is back after [retryAfter]. */
    data class Refused(val retryAfter: Duration) : RateDecision
}

/**
 * Where rate limits are counted. Pelican keeps none itself, so a service picks where they live;
 * [InMemoryRateLimiter] is the one for tests and a single node.
 *
 * Calls block, as an [IdempotencyStore]'s do, and each must be atomic across the nodes sharing the limiter: two
 * requests for the last permit at once must see one [RateDecision.Allowed] between them.
 */
fun interface RateLimiter {
    fun acquire(key: RateLimitKey, limit: RateLimit): RateDecision
}

/**
 * A [RateLimiter] in this process's memory, counting by GCRA: one instant per key, the time its budget is next
 * whole, so the permits come back one interval at a time rather than all at the top of a period.
 */
class InMemoryRateLimiter(private val clock: Clock = Clock.systemUTC()) : RateLimiter {

    private val whole = ConcurrentHashMap<RateLimitKey, Instant>()

    override fun acquire(key: RateLimitKey, limit: RateLimit): RateDecision {
        val now = clock.instant()
        val interval = limit.interval.toJavaDuration()
        // How far ahead of now the budget may be spent before a request waits: all but one permit's worth.
        val tolerance = limit.per.toJavaDuration().minus(interval)
        var decision: RateDecision = RateDecision.Allowed
        // compute holds the key's bin for the whole decision, which is the atomicity the interface promises.
        whole.compute(key) { _, held ->
            val from = held?.takeIf { it.isAfter(now) } ?: now
            val earliest = from.minus(tolerance)
            if (now.isBefore(earliest)) {
                decision = RateDecision.Refused(java.time.Duration.between(now, earliest).toKotlinDuration())
                held
            } else {
                decision = RateDecision.Allowed
                from.plus(interval)
            }
        }
        return decision
    }
}

/** As much of a request as an endpoint's own rate-limit key may read: its headers, its caller and its tenant. */
class RateLimitRequest internal constructor(
    private val credentials: Credentials,
    val caller: Identity?,
    val tenant: Tenant?,
) {
    /** The first line of [name], matched without regard to case. */
    fun header(name: String): String? = credentials.header(name)
}

/** An endpoint's rate limit, declared with `rateLimited(...)`, and the key of its own it adds, if any. */
class RateLimited internal constructor(val limit: RateLimit, internal val key: (RateLimitRequest.() -> String?)?) {
    override fun toString() = "rateLimited($limit)"
}

/**
 * Spends a permit for this request, or refuses it with a 429 naming when to come back. A limiter that cannot answer
 * lets the request through and is reported to [Api.onServerError]: it guards capacity, so its outage is not the
 * service's.
 */
@Suppress("TooGenericExceptionCaught") // whatever a limiter throws, the request goes ahead
internal fun Api.spend(
    endpoint: Endpoint<*, *>,
    limited: RateLimited,
    credentials: Credentials,
    caller: Identity?,
    tenant: Tenant?,
) {
    // The Api refused to build with a limited endpoint and nothing counting it, so this is never reached unbound.
    val limiter = checkNotNull(rateLimiter) { "$endpoint is rate limited and the Api counts no limits" }
    val own = limited.key?.invoke(RateLimitRequest(credentials, caller, tenant))
    val key = RateLimitKey(limited.limit.name, caller?.subject, tenant?.id, own)
    val decision = try {
        limiter.acquire(key, limited.limit)
    } catch (e: RuntimeException) {
        onServerError?.invoke(newReference(), endpoint, e)
        return
    }
    if (decision is RateDecision.Refused) {
        val seconds = decision.retryAfter.inWholeMilliseconds.let { (it + MILLIS - 1) / MILLIS }.coerceAtLeast(1)
        throw ApiException(
            TOO_MANY_REQUESTS,
            RATE_LIMITED_TAG,
            "Too many requests; send again in $seconds seconds.",
            headers = listOf("Retry-After" to seconds.toString()),
        )
    }
}

private const val TOO_MANY_REQUESTS = 429
private const val RATE_LIMITED_TAG = "rate_limited"
private const val MILLIS = 1000L
