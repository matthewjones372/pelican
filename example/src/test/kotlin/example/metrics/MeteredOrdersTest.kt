package example.metrics

import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.github.matthewjones372.pelican.test.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test

/**
 * What the one `metrics(registry)` line in `MeteredOrders.kt` actually
 * produced.
 *
 * Each case asks the same question twice: what came back over the transport,
 * and what the meters say came back. They are asserted together on purpose. A
 * metric that disagrees with the response is worse than a missing one, because
 * it is believed.
 */
class MeteredOrdersTest {

    private val registry = SimpleMeterRegistry()
    private val app = meteredOrders(registry).inMemory("metered-orders")

    /** The (path, status) pairs the request counter holds, which is the series list. */
    private fun recorded(): Set<Pair<String?, String?>> =
        registry.meters
            .filter { it.id.name == "http.server.requests" }
            .map { it.id.getTag("path") to it.id.getTag("status") }
            .toSet()

    /** An endpoint that declares no inputs is called with the empty bag. */
    private fun noInput() = Params(emptyMap(), null)

    private fun tagsFor(path: String, status: Int): Map<String, String> {
        val found = registry.meters.single {
            it.id.name == "http.server.requests" &&
                it.id.getTag("path") == path &&
                it.id.getTag("status") == status.toString()
        }
        return found.id.tags.associate { it.key to it.value }
    }

    @Test
    fun `a success is counted against the template, not against the id that was asked for`() {
        app.response(fetchOrder, 1L) shouldHaveStatus 200
        app.response(fetchOrder, 2L) shouldHaveStatus 200

        // Two ids, one series. This is the reason the template is worth
        // reaching for rather than the request's own path.
        recorded() shouldBe setOf("/orders/{orderId}" to "200")

        tagsFor("/orders/{orderId}", 200) shouldBe mapOf(
            "method" to "GET",
            "path" to "/orders/{orderId}",
            "operation" to "fetchOrder",
            "status" to "200",
            "deprecated" to "false",
        )
    }

    @Test
    fun `a declared failure is counted as the status the declaration gave it`() {
        // The handler returned an `Outcome.Err`, not a throwable, and nothing
        // about that value says 404 — `noSuchOrder` does, and the meter reads
        // it from there.
        app.response(fetchOrder, 99L) shouldHaveStatus 404

        recorded() shouldBe setOf("/orders/{orderId}" to "404")
    }

    @Test
    fun `a 201 is counted as a 201`() {
        app.response(placeOrder, NewOrder("cafetiere")) shouldHaveStatus 201

        recorded() shouldBe setOf("/orders" to "201")
    }

    @Test
    fun `the deprecated endpoint says so on its meters`() {
        app.response(listOrdersV1, noInput()) shouldHaveStatus 200

        tagsFor("/v1/orders", 200)["deprecated"] shouldBe "true"
    }

    @Test
    fun `the timer counts the same requests the counter does`() {
        app.response(fetchOrder, 1L) shouldHaveStatus 200
        app.response(fetchOrder, 99L) shouldHaveStatus 404

        val timers = registry.meters.filter { it.id.name == "http.server.request.duration" }
        withClue("a timer per status, beside each counter: ${timers.map { it.id }}") {
            timers.map { it.id.getTag("status") }.toSet() shouldBe setOf("200", "404")
        }
    }

    @Test
    fun `the meters read back through an ordinary endpoint, and count that one too`() {
        app.response(fetchOrder, 1L) shouldHaveStatus 200

        val table = app.call(readMeters, noInput())

        table shouldContain "path=/orders/{orderId}"
        table shouldContain "operation=fetchOrder"
        table shouldContain "status=200"
        // `/admin/meters` is described like everything else, so it is metered
        // like everything else — but only from the request *after* the one that
        // rendered this table, which is why it is absent here.
        withClue(table) { table shouldContain "http.server.request.duration" }
    }
}
