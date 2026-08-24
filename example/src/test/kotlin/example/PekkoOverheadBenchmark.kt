package example

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.IntCodec
import io.github.matthewjones372.pelican.default
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.handledNow
import io.github.matthewjones372.pelican.pekko.toRoute
import io.github.matthewjones372.pelican.queryParam
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.http.javadsl.model.ContentTypes
import org.apache.pekko.http.javadsl.model.HttpEntities
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.model.HttpResponse
import org.apache.pekko.http.javadsl.model.MediaTypes
import org.apache.pekko.http.javadsl.model.StatusCodes
import org.apache.pekko.http.javadsl.server.Directives
import org.apache.pekko.http.javadsl.server.PathMatchers
import org.apache.pekko.http.javadsl.server.Route
import org.apache.pekko.japi.function.Function
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * What the description costs on Pekko, the same question
 * [OverheadBenchmark] asks of http4k.
 *
 * The same endpoint twice: once described with Pelican and interpreted onto a
 * Pekko `Route`, once written straight against Pekko's routing DSL. Both
 * decode a path segment and an optional query parameter, both encode the same
 * object with the same Jackson mapper, and both are sealed with
 * `Route.function(system)` — so what is left in the difference is the
 * interpreter, not the server.
 *
 * Sealed rather than bound: no socket, no connection handling. That is the
 * same choice the in-memory transport in `pelican-test-pekko` makes, and for
 * the same reason — everything above the wire is exercised, and nothing below
 * it is in the number.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true", disabledReason = "run with -Dbenchmark=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PekkoOverheadBenchmark {

    data class Item(val id: Long, val name: String)

    // `Behaviors.empty<Void>()` is how Pekko's Java DSL spells a guardian with
    // no protocol; see the same exemption in config/detekt/detekt.yml.
    @Suppress("ForbiddenVoid")
    private val system = ActorSystem.create(Behaviors.empty<Void>(), "pelican-pekko-benchmark")

    private val itemId = pathParam<Long>("itemId")
    private val limit = queryParam("limit", IntCodec).default(10)

    private val getItem = endpoint(itemId, limit) {
        get("items" / itemId)
        json<Item>()
    }

    private val described: Function<HttpRequest, java.util.concurrent.CompletionStage<HttpResponse>> = Api(
        endpoints = listOf(getItem handledNow { (id, lim) -> Item(id, "item-$lim") }),
        codecs = JacksonCodecs,
    ).toRoute(system).function(system)

    /** The same behaviour, written the way a Pekko HTTP service would write it. */
    private val handWritten: Function<HttpRequest, java.util.concurrent.CompletionStage<HttpResponse>> = run {
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
     * The same behaviour again, written the way someone who had measured the
     * first one would write it: one directive, the path and query read off the
     * request directly, no `PathMatcher` and no `parameterOptional`.
     *
     * This is what Pelican's interpreter does — it matches the path itself
     * inside a single `extractRequest` — so it is the comparison that says
     * what the description costs, rather than what Pekko's directive stack
     * costs.
     */
    private val handWrittenTuned: Function<HttpRequest, java.util.concurrent.CompletionStage<HttpResponse>> = run {
        val mapper = ObjectMapper().registerKotlinModule()
        val route: Route = Directives.method(org.apache.pekko.http.javadsl.model.HttpMethods.GET) {
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

    /**
     * PathMatchers as before, but the query parameter read off the request
     * instead of through `parameterOptional` — to say which of the two layers
     * the idiomatic route is actually paying for.
     */
    private val handWrittenMatcherOnly: Function<HttpRequest, java.util.concurrent.CompletionStage<HttpResponse>> =
        run {
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

    // ------------------------------------------------------- a body to read
    //
    // The GET cases above never materialise anything: the request has no
    // entity and the response is strict. Reading a body is where Pekko's
    // streams actually turn — `toStrict` on this backend — so the comparison
    // is incomplete without it.

    data class NewItem(val name: String)

    private val newItem = jsonBody<NewItem>()

    private val createItem = endpoint(newItem) {
        post("items")
        json<Item>(status = 201)
    }

    private val describedPost: Function<HttpRequest, java.util.concurrent.CompletionStage<HttpResponse>> = Api(
        endpoints = listOf(createItem handledNow { body -> Item(1, body.name) }),
        codecs = JacksonCodecs,
    ).toRoute(system).function(system)

    private val handWrittenPost: Function<HttpRequest, java.util.concurrent.CompletionStage<HttpResponse>> = run {
        val mapper = ObjectMapper().registerKotlinModule()
        val route: Route = Directives.post {
            Directives.path("items") {
                Directives.extractStrictEntity(java.time.Duration.ofSeconds(10)) { strict ->
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

    private fun post(handler: Function<HttpRequest, java.util.concurrent.CompletionStage<HttpResponse>>): Int =
        handler.apply(
            HttpRequest.POST("/items").withEntity(
                HttpEntities.create(ContentTypes.APPLICATION_JSON, """{"name":"anvil"}"""),
            ),
        ).toCompletableFuture().join().status().intValue()

    private fun measurePost(
        handler: Function<HttpRequest, java.util.concurrent.CompletionStage<HttpResponse>>,
        requests: Int,
    ): Double {
        var sink = 0
        val start = System.nanoTime()
        repeat(requests) { sink += post(handler) }
        val elapsed = System.nanoTime() - start
        check(sink == requests * 201) { "a request failed: $sink" }
        return elapsed.toDouble() / requests
    }

    @AfterAll
    fun stop() {
        system.terminate()
        system.whenTerminated.toCompletableFuture().join()
    }

    private fun once(handler: Function<HttpRequest, java.util.concurrent.CompletionStage<HttpResponse>>): Int =
        handler.apply(HttpRequest.GET("/items/7?limit=25")).toCompletableFuture().join().status().intValue()

    private fun measure(
        handler: Function<HttpRequest, java.util.concurrent.CompletionStage<HttpResponse>>,
        requests: Int,
    ): Double {
        var sink = 0
        val start = System.nanoTime()
        repeat(requests) { sink += once(handler) }
        val elapsed = System.nanoTime() - start
        check(sink == requests * 200) { "a request failed" }
        return elapsed.toDouble() / requests
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    private fun bytesPerRequest(
        handler: Function<HttpRequest, java.util.concurrent.CompletionStage<HttpResponse>>,
        requests: Int = 200_000,
    ): Long {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        repeat(requests) { once(handler) }
        val id = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(id)
        repeat(requests) { once(handler) }
        return (bean.getThreadAllocatedBytes(id) - before) / requests
    }

    @Test
    fun `what the description costs per request on pekko`() {
        val a = described.apply(HttpRequest.GET("/items/7?limit=25")).toCompletableFuture().join()
        val b = handWritten.apply(HttpRequest.GET("/items/7?limit=25")).toCompletableFuture().join()
        check(a.status() == b.status()) { "the two routes disagree: ${a.status()} vs ${b.status()}" }
        println("both answer: ${a.status()}")

        repeat(200_000) { once(described) }
        repeat(200_000) { once(handWritten) }

        val raw = mutableListOf<Double>()
        val pelican = mutableListOf<Double>()
        repeat(9) {
            raw += measure(handWritten, 50_000)
            pelican += measure(described, 50_000)
        }

        val rawMedian = median(raw)
        val pelicanMedian = median(pelican)
        println("pekko raw    %8.0f ns/op  (%.0f..%.0f)".format(rawMedian, raw.min(), raw.max()))
        println("pelican      %8.0f ns/op  (%.0f..%.0f)".format(pelicanMedian, pelican.min(), pelican.max()))
        println("difference   %8.0f ns/op  %.2fx".format(pelicanMedian - rawMedian, pelicanMedian / rawMedian))

        repeat(200_000) { once(handWrittenTuned) }
        val tuned = mutableListOf<Double>()
        repeat(9) { tuned += measure(handWrittenTuned, 50_000) }
        val tunedMedian = median(tuned)
        println("pekko tuned  %8.0f ns/op  (one directive, path read directly)".format(tunedMedian))
        println("vs tuned     %8.0f ns/op  %.2fx".format(pelicanMedian - tunedMedian, pelicanMedian / tunedMedian))

        repeat(200_000) { once(handWrittenMatcherOnly) }
        val matcherOnly = mutableListOf<Double>()
        repeat(9) { matcherOnly += measure(handWrittenMatcherOnly, 50_000) }
        println("pekko matchers %6.0f ns/op  (PathMatchers, query read directly)".format(median(matcherOnly)))

        // The materialising path: a body that has to be read before anything
        // can be decoded.
        repeat(100_000) { post(describedPost) }
        repeat(100_000) { post(handWrittenPost) }
        val rawPost = mutableListOf<Double>()
        val pelicanPost = mutableListOf<Double>()
        repeat(9) {
            rawPost += measurePost(handWrittenPost, 20_000)
            pelicanPost += measurePost(describedPost, 20_000)
        }
        println("-- POST with a JSON body (toStrict materialises)")
        println("pekko raw    %8.0f ns/op".format(median(rawPost)))
        println("pelican      %8.0f ns/op".format(median(pelicanPost)))
        println(
            "difference   %8.0f ns/op  %.2fx".format(
                median(pelicanPost) - median(rawPost),
                median(pelicanPost) / median(rawPost),
            ),
        )

        println("-- bytes allocated per request")
        println("pekko matchers %4d B".format(bytesPerRequest(handWrittenMatcherOnly)))
        println("pekko raw    %6d B".format(bytesPerRequest(handWritten)))
        println("pekko tuned  %6d B".format(bytesPerRequest(handWrittenTuned)))
        println("pelican      %6d B".format(bytesPerRequest(described)))
    }
}
