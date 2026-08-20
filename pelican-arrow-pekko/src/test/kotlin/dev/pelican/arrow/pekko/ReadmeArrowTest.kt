package dev.pelican.arrow.pekko

import dev.pelican.Api
import dev.pelican.ApiError
import dev.pelican.IntCodec
import dev.pelican.StringCodec
import dev.pelican.arrow.ErrorMapper
import dev.pelican.arrow.ensure
import dev.pelican.arrow.ensureNotNull
import dev.pelican.arrow.handledRaise
import dev.pelican.arrow.raise
import dev.pelican.between
import dev.pelican.default
import dev.pelican.endpoint
import dev.pelican.errorJson
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.nonEmpty
import dev.pelican.optional
import dev.pelican.orFail
import dev.pelican.pekko.toRoute
import dev.pelican.queryParam
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.http.javadsl.Http
import org.apache.pekko.http.javadsl.ServerBinding
import org.apache.pekko.http.javadsl.server.Directives
import org.apache.pekko.http.javadsl.server.Route
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The Arrow example in the README, running.
 *
 * Two questions it answers, both of them things a reader asks before adopting
 * any of this: what an *optional* input looks like once a handler raises rather
 * than returns, and what happens to the routes the service already has. Neither
 * is Arrow-specific — a Pelican `Api` is a `Route` here whoever bound its
 * handlers — which is itself the point worth demonstrating rather than
 * asserting.
 *
 * The code below is the code in the README. Keep them the same or delete one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReadmeArrowTest {

    // ------------------------------------------------------------- the domain

    data class Item(val id: Long, val name: String, val tag: String)

    private val store = listOf(
        Item(1, "kettle", "kitchen"),
        Item(2, "lamp", "study"),
        Item(3, "mug", "kitchen"),
    )

    /** The service's own errors. Nothing here knows about HTTP. */
    sealed interface Problem {
        data class NoneMatched(val tag: String) : Problem
        data object TagRequiredForDeepPages : Problem
    }

    private val noMatches = errorJson<ApiError>(404, "No items with that tag")
    private val tagRequired = errorJson<ApiError>(400, "Paging past the first page needs a tag")

    private val toResponse: ErrorMapper<Problem, ApiError> = { problem ->
        when (problem) {
            is Problem.NoneMatched -> noMatches(ApiError(404, "No items tagged ${problem.tag}"))
            is Problem.TagRequiredForDeepPages -> tagRequired(ApiError(400, "Filter by tag to page further"))
        }
    }

    // ------------------------------------------------------------- the inputs
    //
    // `optional()` makes the handler's value nullable; `default(v)` fills it in
    // and keeps it non-null. Both are declared on the parameter, so the OpenAPI
    // document says `required: false` and carries the default without anybody
    // writing that down twice.

    private val tag = queryParam("tag", StringCodec.nonEmpty(), description = "Only items with this tag").optional()
    private val limit = queryParam("limit", IntCodec.between(1, 100)).default(20)
    private val page = queryParam("page", IntCodec.between(1, 50)).default(1)

    private val listItems = endpoint(tag, limit, page) {
        get("items")
        operationId = "listItems"
        json<List<Item>>().orFail(noMatches, tagRequired)
    }

    // ------------------------------------------------------------ the handler

    private fun routes() = listOf(
        // `tag` arrives as String?, `limit` and `page` as Int — the types the
        // declarations imply.
        listItems.handledRaise(toResponse) { (tag, limit, page) ->
            // An optional input that becomes required under some condition is
            // exactly what ensureNotNull is for: it narrows String? to String
            // and raises when it cannot.
            val wanted = if (page == 1) tag else ensureNotNull(tag) { Problem.TagRequiredForDeepPages }

            val found = store.filter { wanted == null || it.tag == wanted }.drop((page - 1) * limit).take(limit)
            ensure(found.isNotEmpty()) { Problem.NoneMatched(wanted ?: "anything") }
            found
        },
    )

    private val api = Api(routes(), JacksonCodecs, title = "Items")

    // --------------------------------------------------- somebody else's routes
    //
    // A health check and a legacy endpoint, written in Pekko's own DSL years
    // before this library existed.

    private val ownRoutes: Route = Directives.concat(
        Directives.get { Directives.path("health") { Directives.complete("ok") } },
        Directives.get { Directives.path("legacy") { Directives.complete("still here") } },
    )

    private val system = ActorSystem.create(Behaviors.empty<Void>(), "arrow-readme")

    /**
     * Both orderings, because "it works if you put Pelican last" is not a
     * property anyone should have to remember. `toRoute` returns a plain
     * `Route`: Pelican's routes reject what they do not describe, so the next
     * route in the concat gets its turn.
     */
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

    private class Answer(val status: Int, val body: String)

    private fun ServerBinding.get(path: String): Answer {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${localAddress().port}$path")).GET().build()
        val res = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
        return Answer(res.statusCode(), res.body())
    }

    // ------------------------------------------------------------------ claims

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `an omitted optional parameter is null, and the defaults still apply`(name: String, server: ServerBinding) {
        val res = server.get("/items")

        assertEquals(200, res.status)
        assertEquals(3, res.body.split("\"id\"").size - 1, res.body)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `the optional parameter filters when it is supplied`(name: String, server: ServerBinding) {
        val res = server.get("/items?tag=kitchen")

        assertEquals(200, res.status)
        assertEquals("""[{"id":1,"name":"kettle","tag":"kitchen"},{"id":3,"name":"mug","tag":"kitchen"}]""", res.body)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `ensureNotNull turns a missing optional into a declared failure`(name: String, server: ServerBinding) {
        val res = server.get("/items?page=2")

        assertEquals(400, res.status)
        assertEquals("""{"status":400,"error":"Filter by tag to page further","detail":null}""", res.body)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `a raise inside the handler is still the declared failure`(name: String, server: ServerBinding) {
        val res = server.get("/items?tag=garage")

        assertEquals(404, res.status)
        assertEquals("""{"status":404,"error":"No items tagged garage","detail":null}""", res.body)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `a parameter that breaks its own rule is a 400 before the handler runs`(name: String, server: ServerBinding) {
        val res = server.get("/items?limit=0")

        assertEquals(400, res.status)
        assertEquals(true, "Cannot decode '0' for 'limit'" in res.body, res.body)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("orderings")
    fun `the routes that were already there still answer`(name: String, server: ServerBinding) {
        assertEquals("ok", server.get("/health").body)
        assertEquals("still here", server.get("/legacy").body)
    }

    /** Neither side swallows the other's 404s. */
    @Test
    fun `an unknown path is a 404 whichever route was asked first`() {
        bindings.values.forEach { server -> assertEquals(404, server.get("/nothing-here").status) }
    }
}
