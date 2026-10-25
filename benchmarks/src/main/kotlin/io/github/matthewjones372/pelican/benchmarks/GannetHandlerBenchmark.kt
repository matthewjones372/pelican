package io.github.matthewjones372.pelican.benchmarks

import io.github.matthewjones372.gannet.Headers
import io.github.matthewjones372.gannet.HttpExchange
import io.github.matthewjones372.gannet.HttpRequest
import io.github.matthewjones372.gannet.HttpVersion
import io.github.matthewjones372.gannet.RequestBody
import io.github.matthewjones372.gannet.ResponseStream
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.gannet.GannetHandler
import io.github.matthewjones372.pelican.gannet.Handlers
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.toHandler
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.queryParam
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.io.IOException
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * HttpArena's `GET /baseline11?a=13&b=42` through Pelican's Gannet handler, inline, from the parsed request to the
 * reply handed to `respond`: what Pelican allocates of its own per request, with no socket and no Gannet runtime.
 * `gc.alloc.rate.norm` is spec 0089's success-path claim.
 *
 * ```bash
 * ./gradlew :benchmarks:jmh -PbenchmarkArgs="-f 1 GannetHandler"
 * ```
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 2, jvmArgsAppend = ["-Xms512m", "-Xmx512m"])
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
open class GannetHandlerBenchmark {

    private val a = queryParam<Long>("a")
    private val b = queryParam<Long>("b")

    private val handler: GannetHandler = api(
        listOf(endpoint(a, b) { get("baseline11"); text() } handledNow { (x, y) -> (x + y).toString() }),
        JacksonCodecs,
    ).toHandler(Handlers.onDispatcher)

    // Gannet builds its request internally; the constructor is public in bytecode, so it is reached by reflection.
    private val request: HttpRequest = HttpRequest::class.java.constructors.single().newInstance(
        "GET", "/baseline11?a=13&b=42", "/baseline11", "a=13&b=42", HttpVersion.HTTP_1_1,
        Headers(listOf("Host" to "localhost:8080")), NoBody,
    ) as HttpRequest

    private val exchange = Answered(request)

    @Benchmark
    fun baseline(): Int {
        handler.handle(exchange)
        return exchange.status
    }

    private class Answered(override val request: HttpRequest) : HttpExchange {
        var status = 0
        override val onCarrier = true

        override fun respond(status: Int, headers: List<Pair<String, String>>, body: ByteArray) {
            this.status = status
        }

        override fun detach() = Unit

        override fun stream(status: Int, headers: List<Pair<String, String>>): ResponseStream =
            throw UnsupportedOperationException("not streamed")

        override fun offload(executor: Executor, work: (HttpExchange) -> Unit) = work(this)
    }

    private object NoBody : RequestBody {
        override val length: Long = 0

        override fun readAll(limit: Long) = ByteArray(0)

        override fun readAll() = ByteArray(0)

        override fun read(into: ByteArray, offset: Int, length: Int) = -1

        override fun read(into: ByteArray, offset: Int, length: Int, timeout: Duration) = -1

        override fun fail(cause: IOException) = Unit
    }
}
