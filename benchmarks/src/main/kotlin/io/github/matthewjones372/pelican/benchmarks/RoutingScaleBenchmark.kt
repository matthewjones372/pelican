package io.github.matthewjones372.pelican.benchmarks

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.toRoute
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.model.HttpResponse
import org.apache.pekko.japi.function.Function
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import io.github.matthewjones372.pelican.http4k.handledNow as handledOnHttp4k
import io.github.matthewjones372.pelican.http4k.toHttpHandler as toHttp4kHandler
import io.github.matthewjones372.pelican.pekko.handledNow as handledOnPekko

/**
 * What matching costs when a service has more than one endpoint.
 *
 * Every other benchmark here uses an API of one endpoint, which measures
 * decoding and rendering and says nothing about the thing that actually varies
 * between services. Pekko's interpreter reduces its routes with
 * `Directives.concat` and http4k's `routes(...)` tries them in order, so both
 * are an ordered scan: a request runs a method comparison and, where that
 * passes, a full path walk per candidate until one matches. Ktor is exempt —
 * `Route.pelican` installs one Ktor route per endpoint and lets Ktor's own tree
 * score them.
 *
 * The endpoint under test is declared *last*, which is the worst case an
 * ordered scan has and the one a service acquires by adding endpoints over
 * time.
 *
 * The decoys are the realistic shape rather than the convenient one: the same
 * segment count with different literals, so a comparison fails late rather than
 * on the first character.
 *
 * A class of its own, so `./gradlew :benchmarks:jmh` stays the six-minute run
 * `what-it-costs.md` documents and this sweep is asked for by name:
 *
 * ```bash
 * ./gradlew :benchmarks:jmh -PbenchmarkArgs="-f 1 RoutingScale"
 * ```
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, jvmArgsAppend = ["-Xms512m", "-Xmx512m"])
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
open class RoutingScaleBenchmark {

    /** One, a small service, and one large enough that a scan would show. */
    @Param("1", "50", "200")
    var endpoints: Int = 1

    private lateinit var http4k: HttpHandler
    private lateinit var request: Request

    private lateinit var pekko: Function<HttpRequest, CompletionStage<HttpResponse>>
    private lateinit var pekkoRequest: HttpRequest

    /**
     * One system for the class. Creating one costs milliseconds against an
     * operation measured in nanoseconds, so it is not per iteration.
     */
    private val system = ActorSystem.create(Behaviors.empty<Void>(), "pelican-routing-scale")

    private val itemId = pathParam<Long>("itemId")

    /** The one under test. Declared last, so an ordered scan reaches it last. */
    private val target = endpoint(itemId) {
        get("items" / itemId)
        text()
    }

    /**
     * Endpoints of the same shape and a different literal. A router that
     * compares segment by segment has to get to the end of each before it can
     * rule it out.
     */
    private fun decoyDescriptions(count: Int) =
        (1..count).map { n ->
            val id = pathParam<Long>("id$n")
            endpoint(id) {
                get("resource$n" / id)
                text()
            }
        }

    @Setup
    fun setUp() {
        val descriptions = decoyDescriptions(endpoints - 1)

        http4k = Api(
            endpoints = descriptions.map { it handledOnHttp4k { _ -> "decoy" } } +
                (target handledOnHttp4k { id -> "item-$id" }),
            codecs = JacksonCodecs,
        ).toHttp4kHandler()

        pekko = Api(
            endpoints = descriptions.map { it handledOnPekko { _ -> "decoy" } } +
                (target handledOnPekko { id -> "item-$id" }),
            codecs = JacksonCodecs,
        ).toRoute(system).function(system)

        request = Request(Method.GET, "/items/7")
        pekkoRequest = HttpRequest.GET("/items/7")

        // That the route under test is the one answering, before anything is
        // timed: a scan that rejected would be the fastest thing here.
        check(http4k(request).status.code == 200) { "http4k did not answer 200" }
        check(answer(pekkoRequest).status().intValue() == 200) { "pekko did not answer 200" }
    }

    @TearDown
    fun stop() {
        system.terminate()
        system.whenTerminated.toCompletableFuture().join()
    }

    private fun answer(req: HttpRequest): HttpResponse =
        pekko.apply(req).toCompletableFuture().join()

    @Benchmark
    fun lastRouteOnHttp4k(): Any = http4k(request)

    @Benchmark
    fun lastRouteOnPekko(): HttpResponse = answer(pekkoRequest)
}
