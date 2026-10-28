package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** How often a caller may call: a named budget, spent per caller and tenant, and a 429 once it is gone. Spec 0096. */
class RateLimitTest {

    private class SteppedClock(var now: Instant = Instant.parse("2026-10-28T12:00:00Z")) : Clock() {
        override fun instant(): Instant = now
        override fun getZone(): ZoneOffset = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?): Clock = this
        fun advance(by: Duration) { now = now.plus(by.toJavaDuration()) }
    }

    private val clock = SteppedClock()
    private val ran = AtomicInteger()
    private val reported = mutableListOf<String>()

    private val bearer = bearerAuth()
    private val caller = authenticated(bearer) { it.subject }
    private val tokens = Authenticator { it.bearerToken()?.let(::Identity) }

    private val shop = tenantFrom(TenantResolver.header("X-Shop"))

    /** Three a minute, so one comes back every twenty seconds. */
    private val orders = rateLimit("orders", 3, per = 1.minutes)

    private val listOrders = endpoint {
        get("orders")
        authenticatedBy(caller)
        tenanted(shop)
        rateLimited(orders)
        text()
    }

    private val countOrders = endpoint {
        get("orders" / "count")
        authenticatedBy(caller)
        tenanted(shop)
        rateLimited(orders)
        text()
    }

    private val status = endpoint {
        get("status")
        rateLimited(orders) { header("X-Forwarded-For") }
        text()
    }

    private fun api(limiter: RateLimiter = InMemoryRateLimiter(clock)) = api(
        listOf(listOrders, countOrders, status).map { ep ->
            ServerEndpoint(ep) { CompletableFuture.completedStage("ok ${ran.incrementAndGet()}" as Any?) }
        },
    ) {
        authenticate(bearer, tokens)
        tenants(shop, InMemoryTenantDirectory(setOf("acme", "globex")))
        rateLimits(limiter)
        onError { _, _, error -> reported += error.message.orEmpty() }
    }

    private val served = api()

    private fun get(path: String, vararg headers: Pair<String, String>, api: Api = served): ClientResponse =
        InMemoryClientTransport(api).send(ClientRequest(Method.GET, "http://api.example$path", headers.toList()))
            .toCompletableFuture().join()

    private fun orders(who: String = "ada", shop: String = "acme", path: String = "/orders", api: Api = served) =
        get(path, "Authorization" to "Bearer $who", "X-Shop" to shop, api = api)

    @Test
    fun `a spent budget is a 429 naming when to come back, and the handler never runs for it`() {
        repeat(3) { orders().status shouldBe 200 }

        val refused = orders()

        refused.status shouldBe 429
        refused.header("Retry-After") shouldBe "20"
        refused.text() shouldContain "rate_limited"
        ran.get() shouldBe 3
    }

    @Test
    fun `the budget comes back one permit at a time`() {
        repeat(3) { orders() }

        clock.advance(19.seconds)
        orders().header("Retry-After") shouldBe "1"

        clock.advance(1.seconds)
        orders().status shouldBe 200
        orders().status shouldBe 429
    }

    @Test
    fun `another caller and another tenant each have their own`() {
        repeat(3) { orders() }

        orders(who = "grace").status shouldBe 200
        orders(shop = "globex").status shouldBe 200
        orders().status shouldBe 429
    }

    @Test
    fun `endpoints naming one limit spend from one budget`() {
        repeat(2) { orders() }
        orders(path = "/orders/count").status shouldBe 200

        orders().status shouldBe 429
    }

    @Test
    fun `an endpoint with no caller keys on what it names`() {
        repeat(3) { get("/status", "X-Forwarded-For" to "198.51.100.7").status shouldBe 200 }

        get("/status", "X-Forwarded-For" to "198.51.100.7").status shouldBe 429
        get("/status", "X-Forwarded-For" to "203.0.113.9").status shouldBe 200
    }

    @Test
    fun `a request refused before the check spends nothing`() {
        repeat(5) { get("/orders", "X-Shop" to "acme").status shouldBe 401 }
        repeat(5) { orders(shop = "initech").status shouldBe 404 }

        repeat(3) { orders().status shouldBe 200 }
    }

    @Test
    fun `a limiter that cannot answer lets the request through, and says so`() {
        val down = RateLimiter { _, _ -> error("the limiter is down") }

        orders(api = api(down)).status shouldBe 200
        reported shouldContainExactly listOf("the limiter is down")
    }

    @Test
    fun `an endpoint with no caller, no tenant and no key of its own is refused when it is built`() {
        shouldThrow<IllegalArgumentException> {
            endpoint {
                get("anyone")
                rateLimited(orders)
                text()
            }
        }.message shouldContain "X-Forwarded-For"
    }

    @Test
    fun `a limited endpoint on an Api that counts no limits fails when the Api is built`() {
        shouldThrow<IllegalArgumentException> {
            api(listOf(ServerEndpoint(status) { CompletableFuture.completedStage("x" as Any?) }))
        }.message shouldContain "rateLimits("
    }

    @Test
    fun `a limit is a positive count over a positive time`() {
        shouldThrow<IllegalArgumentException> { rateLimit("none", 0, per = 1.minutes) }
        shouldThrow<IllegalArgumentException> { rateLimit("never", 1, per = Duration.ZERO) }
        shouldThrow<IllegalArgumentException> { rateLimit(" ", 1, per = 1.minutes) }
    }
}
