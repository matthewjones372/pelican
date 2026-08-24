package dev.pelican.benchmarks

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.pelican.Api
import dev.pelican.IntCodec
import dev.pelican.default
import dev.pelican.div
import dev.pelican.endpoint
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.jsonBody
import dev.pelican.pathParam
import dev.pelican.pekko.handledNow
import dev.pelican.pekko.toRoute
import dev.pelican.queryParam
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.http.javadsl.model.ContentTypes
import org.apache.pekko.http.javadsl.model.HttpEntities
import org.apache.pekko.http.javadsl.model.HttpMethods
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.model.HttpResponse
import org.apache.pekko.http.javadsl.model.StatusCodes
import org.apache.pekko.http.javadsl.server.Directives
import org.apache.pekko.http.javadsl.server.PathMatchers
import org.apache.pekko.http.javadsl.server.Route
import org.apache.pekko.japi.function.Function
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/** What a sealed Pekko route is, once `Route.function(system)` has closed over it. */
private typealias SealedRoute = Function<HttpRequest, CompletionStage<HttpResponse>>

/**
 * What the description costs on Pekko, the same question
 * [Http4kOverheadBenchmark] asks of http4k.
 *
 * The same endpoint several times over: once described with Pelican and
 * interpreted onto a Pekko `Route`, then three ways of writing it straight
 * against Pekko's routing DSL. All of them decode a path segment and an
 * optional query parameter, all encode the same object with the same Jackson
 * mapper, and all are sealed with `Route.function(system)` — so what is left in
 * the difference is the interpreter, not the server.
 *
 * Sealed rather than bound: no socket, no connection handling. That is the
 * same choice the in-memory transport in `pelican-test-pekko` makes, and for
 * the same reason — everything above the wire is exercised, and nothing below
 * it is in the number.
 *
 * Three hand-written variants rather than one because the first number on its
 * own leaves the wrong impression: most of what an idiomatic Pekko route costs
 * is two directives, and saying so is the difference between a benchmark and
 * an advertisement.
 *
 * Mode, units, forks, iterations and the pinned heap are all as
 * [Http4kOverheadBenchmark] sets them, and for the reasons given there. They
 * have to match, or the two files are not answering the same question.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, jvmArgsAppend = ["-Xms512m", "-Xmx512m"])
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Suppress("ForbiddenVoid") // `Behaviors.empty<Void>()` is Pekko's Java DSL; see config/detekt/detekt.yml.
open class PekkoOverheadBenchmark {

    data class Item(val id: Long, val name: String)

    data class NewItem(val name: String)

    // JMH builds the state object once per fork, before warmup, so starting an
    // actor system here costs a second at the top of each fork and nothing per
    // operation. A `@Setup(Level.Iteration)` would restart it eleven times a
    // fork and measure a cold dispatcher.
    private val system = ActorSystem.create(Behaviors.empty<Void>(), "pelican-pekko-benchmark")

    private val itemId = pathParam<Long>("itemId")
    private val limit = queryParam("limit", IntCodec).default(10)

    private val getItem = endpoint(itemId, limit) {
        get("items" / itemId)
        json<Item>()
    }

    private val described: SealedRoute = Api(
        endpoints = listOf(getItem handledNow { (id, lim) -> Item(id, "item-$lim") }),
        codecs = JacksonCodecs,
    ).toRoute(system).function(system)

    /** The same behaviour, written the way a Pekko HTTP service would write it. */
    private val handWritten: SealedRoute = run {
        val mapper = ObjectMapper().registerKotlinModule()
        val route: Route = Directives.get {
            Directives.pathPrefix("items") {
                Directives.path(PathMatchers.longSegment()) { id ->
                    Directives.parameterOptional("limit") { limit ->
                        val lim = limit.map { it.toInt() }.orElse(10)
                        Directives.complete(
                            HttpResponse.create()
                                .withStatus(StatusCodes.OK)
                                .withEntity(
                                    HttpEntities.create(
                                        ContentTypes.APPLICATION_JSON,
                                        mapper.writeValueAsString(Item(id, "item-$lim")),
                                    ),
                                ),
                        )
                    }
                }
            }
        }
        route.function(system)
    }

    /**
     * PathMatchers as before, but the query parameter read off the request
     * instead of through `parameterOptional` — to say which of the two layers
     * the idiomatic route is actually paying for.
     */
    private val handWrittenMatcherOnly: SealedRoute = run {
        val mapper = ObjectMapper().registerKotlinModule()
        val route: Route = Directives.get {
            Directives.pathPrefix("items") {
                Directives.path(PathMatchers.longSegment()) { id ->
                    Directives.extractRequest { req ->
                        val lim = req.uri.query().get("limit").map { it.toInt() }.orElse(10)
                        Directives.complete(
                            HttpResponse.create()
                                .withStatus(StatusCodes.OK)
                                .withEntity(
                                    HttpEntities.create(
                                        ContentTypes.APPLICATION_JSON,
                                        mapper.writeValueAsString(Item(id, "item-$lim")),
                                    ),
                                ),
                        )
                    }
                }
            }
        }
        route.function(system)
    }

    /**
     * The same behaviour again, written the way someone who had measured the
     * first one would write it: one directive, the path and query read off the
     * request directly, no `PathMatcher` and no `parameterOptional`.
     *
     * This is what Pelican's interpreter does — it matches the path itself
     * inside a single `extractRequest` — so it is the comparison that says
     * what the description costs, rather than what Pekko's directive stack
     * costs.
     */
    private val handWrittenTuned: SealedRoute = run {
        val mapper = ObjectMapper().registerKotlinModule()
        val route: Route = Directives.method(HttpMethods.GET) {
            Directives.extractRequest { req ->
                val path = req.uri.path()
                val id = path.removePrefix("/items/").toLongOrNull()
                if (id == null || !path.startsWith("/items/")) {
                    Directives.reject()
                } else {
                    val lim = req.uri.query().get("limit").map { it.toInt() }.orElse(10)
                    Directives.complete(
                        HttpResponse.create()
                            .withStatus(StatusCodes.OK)
                            .withEntity(
                                HttpEntities.create(
                                    ContentTypes.APPLICATION_JSON,
                                    mapper.writeValueAsString(Item(id, "item-$lim")),
                                ),
                            ),
                    )
                }
            }
        }
        route.function(system)
    }

    // ------------------------------------------------------- a body to read
    //
    // The GET cases above never materialise anything: the request has no
    // entity and the response is strict. Reading a body is where Pekko's
    // streams actually turn — `toStrict` on this backend — so the comparison
    // is incomplete without it, and it is the only case here where the routing
    // is not most of the cost.

    private val createItem = endpoint(jsonBody<NewItem>()) {
        post("items")
        json<Item>(status = 201)
    }

    private val describedPost: SealedRoute = Api(
        endpoints = listOf(createItem handledNow { body -> Item(1, body.name) }),
        codecs = JacksonCodecs,
    ).toRoute(system).function(system)

    private val handWrittenPost: SealedRoute = run {
        val mapper = ObjectMapper().registerKotlinModule()
        val route: Route = Directives.post {
            Directives.path("items") {
                Directives.extractStrictEntity(Duration.ofSeconds(10)) { strict ->
                    val body = mapper.readValue(strict.data.utf8String(), NewItem::class.java)
                    Directives.complete(
                        HttpResponse.create()
                            .withStatus(StatusCodes.CREATED)
                            .withEntity(
                                HttpEntities.create(
                                    ContentTypes.APPLICATION_JSON,
                                    mapper.writeValueAsString(Item(1, body.name)),
                                ),
                            ),
                    )
                }
            }
        }
        route.function(system)
    }

    // Built once and reused: a Pekko `HttpRequest` is immutable, and parsing
    // one is a cost every route here would pay identically.
    private val request = HttpRequest.GET("/items/7?limit=25")

    private val postRequest = HttpRequest.POST("/items")
        .withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, """{"name":"anvil"}"""))

    /**
     * `join` on a stage that is already complete, which is what a sealed route
     * over a strict entity returns.
     *
     * It is not free, and it is in every number below in equal measure — the
     * alternative, timing the stage's completion, would mean a callback whose
     * own cost is larger than the thing being measured.
     */
    private fun answer(route: SealedRoute, request: HttpRequest): HttpResponse =
        route.apply(request).toCompletableFuture().join()

    /**
     * That every route actually answers, checked before anything is timed.
     *
     * A Pekko route that rejects completes with 404 without decoding,
     * encoding, or materialising anything — and would report as the fastest
     * thing in the file.
     */
    @Setup
    fun verify() {
        val answers = listOf(described, handWritten, handWrittenMatcherOnly, handWrittenTuned)
            .map { answer(it, request) }
        check(answers.all { it.status() == StatusCodes.OK }) {
            "a route did not answer 200: ${answers.map { it.status() }}"
        }
        check(answer(describedPost, postRequest).status() == StatusCodes.CREATED)
        check(answer(handWrittenPost, postRequest).status() == StatusCodes.CREATED)
    }

    @TearDown
    fun stop() {
        system.terminate()
        system.whenTerminated.toCompletableFuture().join()
    }

    // The returned `HttpResponse` is what stops the JIT deleting the call; see
    // the same note in Http4kOverheadBenchmark.

    @Benchmark
    fun pelican(): HttpResponse = answer(described, request)

    @Benchmark
    fun pekkoIdiomatic(): HttpResponse = answer(handWritten, request)

    @Benchmark
    fun pekkoMatchersOnly(): HttpResponse = answer(handWrittenMatcherOnly, request)

    @Benchmark
    fun pekkoTuned(): HttpResponse = answer(handWrittenTuned, request)

    @Benchmark
    fun pelicanPostBody(): HttpResponse = answer(describedPost, postRequest)

    @Benchmark
    fun pekkoPostBody(): HttpResponse = answer(handWrittenPost, postRequest)
}
