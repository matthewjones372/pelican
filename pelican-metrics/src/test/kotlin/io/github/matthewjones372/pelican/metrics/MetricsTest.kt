package io.github.matthewjones372.pelican.metrics

import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.RefusalReason
import io.github.matthewjones372.pelican.before
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.notFound
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.unauthorized
import io.github.matthewjones372.pelican.wrap
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit

/**
 * The meters, asked of a filter chain rather than of a server.
 *
 * Nothing here starts an HTTP listener, because nothing here needs one: the
 * chain is `List<Filter>.wrap`, the same fold every interpreter builds, and
 * every dimension under test comes from the description. That the three
 * interpreters then answer with the statuses recorded below is the example's
 * `MetricsAcrossBackendsTest`, which asks the same questions over a socket.
 */
class MetricsTest {

    private data class Order(val id: Long)

    private val orderId = pathParam<Long>("orderId", description = "Which order")
    private val gone = errorJson<ApiError>(404, "No order by that id")

    private val fetch = endpoint(orderId) {
        get("orders" / orderId)
        operationId = "fetchOrder"
        json<Order>() orFail gone
    }

    private val retired = endpoint {
        get("orders" / "legacy")
        operationId = "listOrdersLegacy"
        deprecated = true
        json<List<Order>>()
    }

    private val registry: MeterRegistry = SimpleMeterRegistry()

    /** One request through a chain that starts with the meters, ending however [answer] says. */
    private fun metered(endpoint: Endpoint<*, *>, vararg filters: Filter, answer: () -> Any?) =
        (listOf(metrics(registry)) + filters.toList())
            .wrap { CompletableFuture.completedStage(answer()) }
            .invoke(Params(emptyMap(), null, endpoint))
            .toCompletableFuture()

    /** The tags on the one meter called [name], or a failure naming what was there instead. */
    private fun tagsOf(name: String): Map<String, String> {
        val found = registry.meters.filter { it.id.name == name }
        withClue("expected exactly one $name meter, found ${found.map { it.id.tags }}") { found.size shouldBe 1 }
        return found.single().id.tags.associate { it.key to it.value }
    }

    private fun countOf(tags: Map<String, String>): Double =
        withClue("no counter tagged $tags among ${registry.meters.map { it.id }}") {
            registry.find("http.server.requests")
                .tags(tags.map { Tag.of(it.key, it.value) })
                .counter()
                .shouldNotBeNull()
                .count()
        }

    private fun meterNames(): Set<String> = registry.meters.map { it.id.name }.toSet()

    @Test
    fun `every dimension comes off the description`() {
        metered(fetch) { ok(Order(7)) }.join()

        tagsOf("http.server.requests") shouldBe mapOf(
            "method" to "GET",
            "path" to "/orders/{orderId}",
            "operation" to "fetchOrder",
            "status" to "200",
            "deprecated" to "false",
        )
    }

    @Test
    fun `the path tag is the template rather than the path the caller asked for`() {
        // The point of the whole exercise: a thousand orders are one series
        // rather than a thousand. The description is what makes that possible,
        // and it is read here rather than reverse-engineered from a URL.
        metered(fetch) { ok(Order(7)) }.join()
        metered(fetch) { ok(Order(8)) }.join()

        tagsOf("http.server.requests")["path"] shouldBe "/orders/{orderId}"
        countOf(mapOf("path" to "/orders/{orderId}", "status" to "200")) shouldBe 2.0
    }

    @Test
    fun `both meters are recorded, and the timer saw a duration`() {
        metered(fetch) { ok(Order(7)) }.join()

        meterNames() shouldBe setOf("http.server.requests", "http.server.request.duration")

        val timer = registry.find("http.server.request.duration").timer().shouldNotBeNull()
        timer.count() shouldBe 1L
        timer.totalTime(TimeUnit.NANOSECONDS) shouldBeGreaterThan 0.0
    }

    @Test
    fun `a declared failure is counted under the status its declaration gave it`() {
        metered(fetch) { gone(ApiError(404, "no such order")) }.join()

        tagsOf("http.server.requests")["status"] shouldBe "404"
    }

    @Test
    fun `a throwable is counted as the status it will be rendered as`() {
        shouldThrow<CompletionException> { metered(fetch) { notFound("no such order") }.join() }

        tagsOf("http.server.requests")["status"] shouldBe "404"
    }

    @Test
    fun `something nobody described is counted as the 500 it becomes`() {
        shouldThrow<CompletionException> {
            metered(fetch) { throw IllegalStateException("the database is on fire") }.join()
        }

        tagsOf("http.server.requests")["status"] shouldBe "500"
    }

    @Test
    fun `a refusal raised by a filter further in is counted too`() {
        // A `before` throws where it stands rather than failing a stage, so
        // this is the request a metrics filter built on `handle` alone would
        // miss — and a 401 is what a rate-of-refusals graph is drawn for.
        shouldThrow<CompletionException> {
            metered(fetch, before { unauthorized("present a token") }) { ok(Order(7)) }.join()
        }

        tagsOf("http.server.requests")["status"] shouldBe "401"
    }

    @Test
    fun `the refusal still reaches the interpreter unchanged`() {
        // Measuring a request must not alter it: the throwable the chain was
        // going to raise is the throwable it raises.
        val failure = shouldThrow<CompletionException> {
            metered(fetch, before { unauthorized("present a token") }) { ok(Order(7)) }.join()
        }

        failure.cause.shouldNotBeNull().message shouldBe "present a token"
    }

    @Test
    fun `a deprecated endpoint says so, so that its remaining callers can be found`() {
        metered(retired) { listOf(Order(7)) }.join()

        tagsOf("http.server.requests")["deprecated"] shouldBe "true"
    }

    @Test
    fun `an endpoint that never named itself is tagged rather than left out`() {
        val anonymous = endpoint {
            get("health")
            json<Order>()
        }

        metered(anonymous) { Order(1) }.join()

        tagsOf("http.server.requests")["operation"] shouldBe "unnamed"
    }

    @Test
    fun `one pair of meters per endpoint and status, however many requests arrive`() {
        repeat(3) { metered(fetch) { ok(Order(7)) }.join() }
        metered(fetch) { gone(ApiError(404, "no such order")) }.join()

        // Two statuses, so two counters and two timers. The meters are cached
        // against the description, which makes the number of series a property
        // of the service's shape rather than of its traffic.
        registry.meters.size shouldBe 4
        countOf(mapOf("status" to "200")) shouldBe 3.0
        countOf(mapOf("status" to "404")) shouldBe 1.0
    }

    @Test
    fun `a request that matched no description is passed through unmetered`() {
        val chain = listOf(metrics(registry)).wrap { CompletableFuture.completedStage("handled" as Any?) }

        chain(Params(emptyMap(), null, endpoint = null)).toCompletableFuture().join() shouldBe "handled"

        registry.meters.shouldBeEmpty()
    }

    @Test
    fun `the prefix is what both names are built from`() {
        listOf(metrics(registry, prefix = "orders.http"))
            .wrap { CompletableFuture.completedStage(ok(Order(7)) as Any?) }
            .invoke(Params(emptyMap(), null, fetch))
            .toCompletableFuture()
            .join()

        meterNames() shouldBe setOf("orders.http.requests", "orders.http.request.duration")
    }

    @Test
    fun `a blank prefix is refused where it is configured rather than on a request`() {
        val failure = shouldThrow<IllegalArgumentException> { metrics(registry, prefix = " ") }

        failure.message.shouldNotBeNull()
    }

    // ------------------------------------------------ the traffic no filter sees

    @Test
    fun `a refusal is counted by reason, status and the route that refused`() {
        refusalCounter(registry).refused(RefusalReason.BODY_LIMIT, 413, "/orders/{orderId}")

        tagsOf("http.server.refusals") shouldBe mapOf(
            "reason" to "body_limit",
            "status" to "413",
            "path" to "/orders/{orderId}",
        )
    }

    /**
     * The tag that decides whether this meter is safe to publish. An unmatched
     * request carries whatever path a caller chose, so counting under it would
     * be one series per probe — the metric as attack surface.
     */
    @Test
    fun `a refusal with no route is counted under one constant, not under the path`() {
        val counter = refusalCounter(registry)
        counter.refused(RefusalReason.UNMATCHED, 404, null)
        counter.refused(RefusalReason.UNMATCHED, 404, null)

        tagsOf("http.server.refusals")["path"] shouldBe "_unmatched"
        registry.find("http.server.refusals").counter().shouldNotBeNull().count() shouldBe 2.0
    }

    @Test
    fun `the request meters are untouched by a refusal, so no dashboard changes meaning`() {
        refusalCounter(registry).refused(RefusalReason.ACCEPT, 406, "/orders/{orderId}")

        meterNames() shouldBe setOf("http.server.refusals")
    }

    @Test
    fun `the prefix moves the refusal counter too`() {
        refusalCounter(registry, prefix = "orders.http").refused(RefusalReason.DECODE, 400, null)

        meterNames() shouldBe setOf("orders.http.refusals")
        shouldThrow<IllegalArgumentException> { refusalCounter(registry, prefix = " ") }
            .message.shouldNotBeNull()
    }
}
