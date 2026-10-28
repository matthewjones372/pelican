package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.newReference
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * [permits] requests every [per], and up to [burst] of them at once: a bucket holding [burst] permits, refilled one
 * every `per / permits`. The shape a store's `take` is asked about, so a persistent store can count it as it likes.
 */
class TokenBucket(val permits: Int, val per: Duration, val burst: Int = permits) {
    init {
        require(permits > 0 && per.isPositive()) { "A bucket refills a positive number of permits over a positive time." }
        require(burst > 0) { "A bucket holds at least one permit." }
    }

    /** The time one permit takes to come back. */
    val interval: Duration get() = per / permits

    override fun toString() = "$permits every $per" + if (burst == permits) "" else ", bursts of $burst"
}

/** A [TokenBucket] under a name, shared by every endpoint that names it. Made with [rateLimit]. */
class RateLimit internal constructor(val name: String, val bucket: TokenBucket) {
    override fun toString() = "$bucket ($name)"
}

/** [permits] requests every [per] under [name], up to [burst] at once; endpoints naming one limit share it. */
fun rateLimit(name: String, permits: Int, per: Duration, burst: Int = permits): RateLimit {
    require(name.isNotBlank()) { "A rate limit is named, so endpoints sharing a budget can say so." }
    return RateLimit(name, TokenBucket(permits, per, burst))
}

/** What a [RateLimitStore] says to one `take`. */
sealed interface Taken {
    /** The permits were taken, and [remaining] are left in the bucket. */
    data class Allowed(val remaining: Int) : Taken

    /** They were not, and nothing was taken; enough are back after [retryAfter]. */
    data class Denied(val retryAfter: Duration) : Taken
}

/**
 * Where rate limits are counted. Pelican keeps none itself, so a service picks where they live;
 * [InMemoryRateLimitStore] is the one for tests and a single node, and a persistent one is the service's.
 *
 * Calls block, as an [IdempotencyStore]'s do, and each must be atomic across the nodes sharing the store: two
 * requests for the last permit at once must see one [Taken.Allowed] between them. A denial takes nothing, so a
 * caller that waits as told gets in.
 */
fun interface RateLimitStore {
    /** Takes [cost] permits from [bucket] under [key], or says how long until it could. */
    fun take(key: String, bucket: TokenBucket, cost: Int): Taken
}

/**
 * A [RateLimitStore] in this process's memory, counting by GCRA: one instant per key, the time its bucket is next
 * full, so permits come back one interval at a time rather than all at the top of a period.
 */
class InMemoryRateLimitStore(private val clock: Clock = Clock.systemUTC()) : RateLimitStore {

    private val full = ConcurrentHashMap<String, Instant>()

    override fun take(key: String, bucket: TokenBucket, cost: Int): Taken {
        val now = clock.instant()
        val interval = bucket.interval.toJavaDuration()
        // How far past now a bucket may be spent: all of it.
        val capacity = interval.multipliedBy(bucket.burst.toLong())
        var taken: Taken = Taken.Allowed(0)
        // compute holds the key's bin for the whole decision, which is the atomicity the interface promises.
        full.compute(key) { _, held ->
            val after = (held?.takeIf { it.isAfter(now) } ?: now).plus(interval.multipliedBy(cost.toLong()))
            val over = java.time.Duration.between(now, after).minus(capacity)
            if (over.isPositive) {
                taken = Taken.Denied(over.toKotlinDuration())
                held
            } else {
                taken = Taken.Allowed((-over.toNanos() / interval.toNanos()).toInt())
                after
            }
        }
        return taken
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
    val store = checkNotNull(rateLimits) { "$endpoint is rate limited and the Api counts no limits" }
    val own = limited.key?.invoke(RateLimitRequest(credentials, caller, tenant))
    val key = keyOf(limited.limit.name, caller?.subject, tenant?.id, own)
    val decision = try {
        store.take(key, limited.limit.bucket, cost = 1)
    } catch (e: RuntimeException) {
        onServerError?.invoke(newReference(), endpoint, e)
        return
    }
    if (decision is Taken.Denied) {
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

/**
 * Whose bucket a request takes from: the limit's name, the caller, the tenant and the endpoint's own key, each
 * length-prefixed so no value can pass for another's, and none of them a character a database column refuses.
 */
internal fun keyOf(limit: String, subject: String?, tenant: String?, own: String?): String =
    listOf(limit, subject, tenant, own).joinToString("/") { it?.let { part -> "${part.length}:$part" } ?: "-" }
