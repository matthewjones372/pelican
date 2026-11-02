package dev.surefold.pelican.benchmarks

import dev.surefold.pelican.Method
import dev.surefold.pelican.ParamKey
import dev.surefold.pelican.ServerEndpoint
import dev.surefold.pelican.div
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.pathParam
import dev.surefold.pelican.queryParam
import dev.surefold.pelican.spi.RouteIndex
import dev.surefold.pelican.spi.routeIndex
import dev.surefold.pelican.spi.withoutDotSegments
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A raw request path to its endpoint, over HttpArena's routes: what `-prof gc` reads as `gc.alloc.rate.norm` is
 * spec 0089's claim that routing `/baseline11` allocates nothing, and that a capture allocates only its value.
 *
 * ```bash
 * ./gradlew :benchmarks:jmh -PbenchmarkArgs="-f 1 RequestPath"
 * ```
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 2, jvmArgsAppend = ["-Xms512m", "-Xmx512m"])
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
open class RequestPathBenchmark {

    private val a = queryParam<Long>("a")
    private val b = queryParam<Long>("b")
    private val count = pathParam<Int>("count")

    private val index: RouteIndex = listOf(
        endpoint(a, b) { get("baseline11"); text() },
        endpoint(a, b) { post("baseline11"); text() },
        endpoint { get("pipeline"); text() },
        endpoint(count) { get("json" / count); text() },
    ).map { ServerEndpoint(it) { CompletableFuture.completedFuture(null) } }.routeIndex()

    // Fields, so the JIT cannot fold the walk away.
    var baselinePath = "/baseline11"
    var jsonPath = "/json/50"
    private val bag = HashMap<ParamKey<*>, Any?>()

    @Benchmark
    fun baseline(): ServerEndpoint? = index.match(Method.GET, checkNotNull(withoutDotSegments(baselinePath)), bag)

    @Benchmark
    fun capture(): ServerEndpoint? = index.match(Method.GET, checkNotNull(withoutDotSegments(jsonPath)), bag)
}
