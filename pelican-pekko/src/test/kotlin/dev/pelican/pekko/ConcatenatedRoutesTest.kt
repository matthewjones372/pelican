package dev.pelican.pekko

import dev.pelican.Api
import dev.pelican.cors
import dev.pelican.div
import dev.pelican.endpoint
import dev.pelican.headerParam
import dev.pelican.optional
import dev.pelican.pathParam
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.http.javadsl.Http
import org.apache.pekko.http.javadsl.ServerBinding
import org.apache.pekko.http.javadsl.model.HttpMethods
import org.apache.pekko.http.javadsl.server.Directives
import org.apache.pekko.http.javadsl.server.Route
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

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
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcatenatedRoutesTest {

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

    private val api = Api(
        endpoints = listOf(
            hello handledNow { who -> "Hello, $who!" },
            note handledNow { carried -> "noted ${carried ?: "-"}" },
        ),
        cors = cors(allowed),
    )

    /**
     * Somebody else's routes. `OPTIONS /custom` is answered deliberately: it is
     * how the test tells "Pelican rejected and this ran" apart from "nothing
     * ran at all", which a 404 on its own cannot.
     */
    private val ownRoutes: Route = Directives.concat(
        Directives.get { Directives.path("custom") { Directives.complete("mine") } },
        Directives.method(HttpMethods.OPTIONS) {
            Directives.path("custom") { Directives.complete("mine, too") }
        },
    )

    // One system for the class; each ordering gets its own binding on its own port.
    private val system = ActorSystem.create(Behaviors.empty<Void>(), "pelican-concat-test")

    private val bindings: Map<String, ServerBinding> = mapOf(
        "pelican first" to bind(Directives.concat(api.toRoute(system), ownRoutes)),
        "pelican last" to bind(Directives.concat(ownRoutes, api.toRoute(system))),
    )

    private fun bind(route: Route): ServerBinding =
        Http.get(system).newServerAt("127.0.0.1", 0).bind(route).toCompletableFuture().join()

    @Suppress("unused") // @MethodSource
    private fun orderings(): List<Array<Any>> = bindings.map { (name, binding) -> arrayOf(name, binding) }

    @AfterAll
    fun stop() {
        bindings.values.forEach { it.unbind().toCompletableFuture().join() }
        system.terminate()
        system.whenTerminated.toCompletableFuture().join()
    }

    // ------------------------------------------------------------------ probe

    private class Answer(val status: Int, val body: String, val headers: HttpResponse<String>) {
        fun header(name: String): String? = headers.headers().firstValue(name).orElse(null)
    }

    private fun ServerBinding.send(
        method: String,
        path: String,
        vararg headers: Pair<String, String>,
    ): Answer {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${localAddress().port}$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .apply { headers.forEach { (n, v) -> header(n, v) } }
            .build()
        val res = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        return Answer(res.statusCode(), res.body(), res)
    }

    private fun ServerBinding.preflight(path: String, method: String, origin: String = allowed): Answer =
        send("OPTIONS", path, "Origin" to origin, "Access-Control-Request-Method" to method)

    // ------------------------------------------------------------------ claims

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `the endpoints are served, with the CORS headers, alongside routes of your own`(
        name: String,
        server: ServerBinding,
    ) {
        val res = server.send("GET", "/hello/matt", "Origin" to allowed)

        assertEquals(200, res.status)
        assertEquals("Hello, matt!", res.body)
        assertEquals(allowed, res.header("Access-Control-Allow-Origin"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `your own route still answers`(name: String, server: ServerBinding) {
        val res = server.send("GET", "/custom")

        assertEquals(200, res.status)
        assertEquals("mine", res.body)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `a preflight for a described path is answered from the description`(
        name: String,
        server: ServerBinding,
    ) {
        val res = server.preflight("/notes", method = "POST")

        assertEquals(204, res.status)
        assertEquals(allowed, res.header("Access-Control-Allow-Origin"))
        assertEquals("POST", res.header("Access-Control-Allow-Methods"))
        assertEquals("X-Trace-Id", res.header("Access-Control-Allow-Headers"))
    }

    /**
     * The one that matters. Pelican describes no `/custom`, so its preflight
     * route has no business answering for it — and the proof that it did not is
     * the other route's own answer coming back.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `an OPTIONS for a path Pelican does not describe falls through to your route`(
        name: String,
        server: ServerBinding,
    ) {
        val res = server.preflight("/custom", method = "GET")

        assertEquals(200, res.status)
        assertEquals("mine, too", res.body)
        assertNull(res.header("Access-Control-Allow-Origin"))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `a path nobody describes is still a 404, not a preflight and not a 405`(
        name: String,
        server: ServerBinding,
    ) {
        assertEquals(404, server.send("GET", "/nothing/here").status)
        assertEquals(404, server.preflight("/nothing/here", method = "GET").status)
    }
}
