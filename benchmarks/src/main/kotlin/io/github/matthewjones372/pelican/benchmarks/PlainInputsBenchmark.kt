package io.github.matthewjones372.pelican.benchmarks

import io.github.matthewjones372.pelican.QueryParam
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.parseFormBody
import io.github.matthewjones372.pelican.queryParam
import io.github.matthewjones372.pelican.spi.EndpointPlan
import io.github.matthewjones372.pelican.spi.RequestView
import io.github.matthewjones372.pelican.spi.Slots
import io.github.matthewjones372.pelican.spi.plan
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit

/**
 * HttpArena's `?a=13&b=42` into the values its endpoint declares: `plan` is spec 0089's slots and one pass over
 * the query, `form` the reading it replaced, every pair decoded as a form and grouped into a map.
 * `gc.alloc.rate.norm` reads spec 0089's claim that plain inputs allocate only the decoded values.
 *
 * ```bash
 * ./gradlew :benchmarks:jmh -PbenchmarkArgs="-f 1 PlainInputs"
 * ```
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 2, jvmArgsAppend = ["-Xms512m", "-Xmx512m"])
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
open class PlainInputsBenchmark {

    private val a = queryParam<Long>("a")
    private val b = queryParam<Long>("b")
    private val baseline = endpoint(a, b) { get("baseline11"); text() }
    private val plan: EndpointPlan = baseline.plan()

    // A field, so the JIT cannot fold the scan away.
    var query = "a=13&b=42"

    private val view = object : RequestView {
        override val method = "GET"
        override val rawPath = "/baseline11"
        override val rawQuery: String get() = query

        override fun header(name: String): String? = null

        override fun headers(name: String): List<String> = emptyList()
    }

    @Benchmark
    fun plan(): Slots = plan.slots().also { plan.decodePlainInputs(view, it) }

    @Benchmark
    fun form(): Map<QueryParam<*>, Any?> {
        val values = parseFormBody(query).groupBy({ it.first }, { it.second })
        return baseline.queries.associateWith { q -> q.codec.decode(q.name, values.getValue(q.name).first()) }
    }
}
