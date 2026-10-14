package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.cors
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.headerParam
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.pathParam
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.apache.pekko.http.javadsl.model.HttpMethods
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.server.Directives
import org.apache.pekko.http.javadsl.server.Route
import org.apache.pekko.http.javadsl.testkit.TestRoute
import org.apache.pekko.http.javadsl.testkit.TestRouteResult
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * A Pelican route is a Pekko `Route`, and a service that has one usually has
 * routes of its own to put beside it — a health check, a websocket, something
 * written by hand years before this library existed. `Directives.concat` is how
 * Pekko joins them, and CORS is the part of Pelican most likely to break in
 * that arrangement: the preflight route matches `OPTIONS` on *any* path, so a
 * careless version of it would swallow every `OPTIONS` in the service.
 *
 * It rejects instead, for a path it does not describe. These are the claims
 * that keeps true, asserted with the route concatenated in both orders —
 * Pelican first and Pelican last — because "it works if you put it last" is not
 * a property anybody wants to have to remember.
 *
 * Run through Pekko's own route testkit rather than over a socket. Everything
 * asked here is decided by the routing tree — which route matches, which
 * rejects, what the rejection turns into — and `TestRoute.run` seals the route
 * exactly as a bound server does. What a socket would add is chunk framing and
 * connection handling, which the streaming tests and the example module's
 * bound-server tests cover; repeating it here bought two ports and a client.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcatenatedRoutesTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoRouteTestKit("pelican-concat-test")
    }

    private val allowed = "https://app.example.com"

    private val name = pathParam<String>("name")
    private val trace = headerParam<String>("X-Trace-Id").optional()

    private val hello = endpoint(name) {
        get("hello" / name)
        text()
    }

    private val note = endpoint(trace) {
        post("notes")
        text()
    }

    private val api = api(
        endpoints = listOf(
            hello handledNow { who -> "Hello, $who!" },
            note handledNow { carried -> "noted ${carried ?: "-"}" },
        ),
    ) {
        cors = cors(allowed)
    }

    private val ownRoutes: Route = Directives.concat(
        Directives.get { Directives.path("custom") { Directives.complete("mine") } },
        Directives.method(HttpMethods.OPTIONS) {
            Directives.path("custom") { Directives.complete("mine, too") }
        },
    )

    @Suppress("unused") // @MethodSource
    private fun orderings(): List<Array<Any>> = listOf(
        arrayOf("pelican first", Directives.concat(api.toRoute(pekko.system()), ownRoutes)),
        arrayOf("pelican last", Directives.concat(ownRoutes, api.toRoute(pekko.system()))),
    )

    // ------------------------------------------------------------------ probe

    private fun TestRoute.get(path: String, vararg headers: Pair<String, String>): TestRouteResult =
        run(headers.fold(HttpRequest.GET(path)) { req, (n, v) -> req.addHeader(rawHeader(n, v)) })

    private fun TestRoute.preflight(path: String, method: String, origin: String = allowed): TestRouteResult =
        run(
            HttpRequest.OPTIONS(path)
                .addHeader(rawHeader("Origin", origin))
                .addHeader(rawHeader("Access-Control-Request-Method", method)),
        )

    private fun rawHeader(name: String, value: String) =
        org.apache.pekko.http.javadsl.model.HttpHeader.parse(name, value)

    /** Null when the header is absent, which is what several claims here are. */
    private fun TestRouteResult.headerOrNull(name: String): String? =
        response().getHeader(name).map { it.value() }.orElse(null)

    private fun Route.test(): TestRoute = pekko.testRoute(this)

    // ----------------------------------------------------------------- claims

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `the endpoints are served, with the CORS headers, alongside routes of your own`(name: String, route: Route) {
        val res = route.test().get("/hello/matt", "Origin" to allowed)

        res.assertStatusCode(200).assertEntity("Hello, matt!")
        res.headerOrNull("Access-Control-Allow-Origin") shouldBe allowed
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `your own route still answers`(name: String, route: Route) {
        route.test().get("/custom").assertStatusCode(200).assertEntity("mine")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `a preflight for a described path is answered from the description`(name: String, route: Route) {
        val res = route.test().preflight("/notes", method = "POST")

        res.assertStatusCode(204)
        res.headerOrNull("Access-Control-Allow-Origin") shouldBe allowed
        res.headerOrNull("Access-Control-Allow-Methods") shouldBe "POST"
        res.headerOrNull("Access-Control-Allow-Headers") shouldBe "X-Trace-Id"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `an OPTIONS for a path Pelican does not describe falls through to your route`(name: String, route: Route) {
        val res = route.test().preflight("/custom", method = "GET")

        res.assertStatusCode(200).assertEntity("mine, too")
        res.headerOrNull("Access-Control-Allow-Origin").shouldBeNull()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `a path nobody describes is still a 404, not a preflight and not a 405`(name: String, route: Route) {
        route.test().get("/nothing/here").assertStatusCode(404)
        route.test().preflight("/nothing/here", method = "GET").assertStatusCode(404)
    }
}
