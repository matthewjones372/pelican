package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.of
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.responseHeader
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.testkit.TestRoute
import org.apache.pekko.http.javadsl.testkit.TestRouteResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * A declared failure carrying a header, on the wire.
 *
 * The values travel on the failure rather than through `Params.setHeader`, and
 * this is the difference that buys: the same handler answers both ways, and the
 * `Retry-After` is on the 429 and on nothing else. A header set on the request's
 * `Params` would have gone on whichever response came back, which is right for a
 * correlation id and wrong for this.
 *
 * Run through Pekko's own route testkit rather than over a socket: what is
 * asked here is what the response carries, and `TestRoute.run` seals the route
 * exactly as a bound server does. The socket-level version of the same claim,
 * on all three backends at once, is the example module's `AllBackendsTest`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FailureHeadersTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoRouteTestKit("pelican-failure-headers-test")
    }

    data class Widget(val id: Long)

    data class Problem(val message: String)

    private val widgetId = pathParam<Long>("widgetId")
    private val retryAfter = responseHeader<Long>("Retry-After", "Seconds to wait")

    private val throttled = errorJson<Problem>(429, "Too many requests", retryAfter)

    private val fetch = endpoint(widgetId) {
        get("widgets" / widgetId)
        json<Widget>() orFail throttled
    }

    /** Zero is the id that is being rate limited; anything else is served. */
    private val api = api(
        endpoints = listOf(
            fetch handledOrFail { id ->
                if (id == 0L) throttled(Problem("Slow down"), retryAfter of 30L) else ok(Widget(id))
            },
        ),
        codecs = JacksonCodecs,
    )

    /** The same endpoint bound by a handler that forgets the header it promised. */
    private val forgetful = api(
        endpoints = listOf(fetch handledOrFail { _ -> throttled(Problem("Slow down")) }),
        codecs = JacksonCodecs,
    )

    private fun Api.test(): TestRoute = pekko.testRoute(toRoute(pekko.system()))

    private fun TestRoute.get(path: String): TestRouteResult = run(HttpRequest.GET(path))

    private fun TestRouteResult.headerOrNull(name: String): String? =
        response().getHeader(name).map { it.value() }.orElse(null)

    @Test
    fun `the failure carries its payload and its header`() {
        val res = api.test().get("/widgets/0")

        res.assertStatusCode(429).assertEntity("""{"message":"Slow down"}""")
        res.headerOrNull("Retry-After") shouldBe "30"
    }

    @Test
    fun `and the success this same handler returns does not`() {
        val res = api.test().get("/widgets/7")

        res.assertStatusCode(200)
        res.headerOrNull("Retry-After").shouldBeNull()
    }

    @Test
    fun `a required header the handler left out is a 500, not a 429 missing it`() {
        val res = forgetful.test().get("/widgets/0")

        res.assertStatusCode(500)
        res.headerOrNull("Retry-After").shouldBeNull()
    }
}
