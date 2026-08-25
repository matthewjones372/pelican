package io.github.matthewjones372.pelican.http4k

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.junit.jupiter.api.Test

/**
 * That matching does not start depending on the endpoint count again.
 *
 * It used to. Both routers try their entries in turn, so two hundred endpoints
 * cost about 150µs a request — the same registered by hand, because the scan was
 * never the interpreter's doing. `RouteIndex` replaced the list with a trie and
 * the number stopped sloping; this is what says so on every build.
 *
 * **A ratio, not a duration.** A wall-clock threshold on a shared runner is a
 * flaky test with extra steps: the machine decides whether it passes. What is
 * asserted is two hundred endpoints against one *in the same JVM, moments
 * apart*, which cancels the machine out — and is what a regression moves, from
 * roughly one to roughly six hundred.
 *
 * Interleaved and taking the best round rather than the mean, because a
 * scheduler that steals time steals it from one side or the other and only ever
 * makes a round slower.
 */
class RoutingScaleTest {

    private val itemId = pathParam<Long>("itemId")

    /** The endpoint under test, declared last: the worst case a scan has. */
    private fun handlerOf(endpoints: Int): HttpHandler {
        val decoys = (1..endpoints - 1).map { n ->
            val id = pathParam<Long>("id$n")
            endpoint(id) { get("resource$n" / id); text() } handledNow { _ -> "decoy" }
        }
        val target = endpoint(itemId) { get("items" / itemId); text() } handledNow { id -> "item-$id" }
        return Api(endpoints = decoys + target, codecs = JacksonCodecs).toHttpHandler()
    }

    private val request = Request(Method.GET, "/items/7")

    private fun timed(handler: HttpHandler, calls: Int): Long {
        val started = System.nanoTime()
        repeat(calls) { handler(request) }
        return System.nanoTime() - started
    }

    @Test
    fun `matching does not slow down as endpoints are added`() {
        val one = handlerOf(1)
        val many = handlerOf(ENDPOINTS)

        withClue("the endpoint under test is not the one answering") {
            one(request).bodyString() shouldBe "item-7"
            many(request).bodyString() shouldBe "item-7"
        }

        // Enough for the JIT to have made its decisions about both.
        repeat(WARMUP) { one(request); many(request) }

        // Interleaved, best of several: a round that was interrupted is slower
        // than one that was not, never faster, so the minimum is the round that
        // measured the code rather than the machine.
        var best = Double.MAX_VALUE
        repeat(ROUNDS) {
            val small = timed(one, CALLS)
            val large = timed(many, CALLS)
            best = minOf(best, large.toDouble() / small.toDouble())
        }

        withClue(
            "matching $ENDPOINTS endpoints cost ${"%.1f".format(best)}x matching one. " +
                "An ordered scan over the endpoint list would be about $ENDPOINTS times, which is what " +
                "this is here to catch — see RouteIndex.",
        ) {
            best shouldBeLessThan ALLOWED
        }
    }

    private companion object {
        const val ENDPOINTS = 200
        const val WARMUP = 20_000
        const val CALLS = 20_000
        const val ROUNDS = 5

        /**
         * Three, against a regression that would be several hundred. Wide
         * enough that a busy runner cannot fail it and narrow enough that
         * putting the scan back cannot pass.
         */
        const val ALLOWED = 3.0
    }
}
