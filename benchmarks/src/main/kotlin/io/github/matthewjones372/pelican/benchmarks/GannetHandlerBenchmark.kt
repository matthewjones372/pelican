package io.github.matthewjones372.pelican.benchmarks

import io.github.matthewjones372.gannet.Headers
import io.github.matthewjones372.gannet.HttpExchange
import io.github.matthewjones372.gannet.HttpRequest
import io.github.matthewjones372.gannet.HttpVersion
import io.github.matthewjones372.gannet.RequestBody
import io.github.matthewjones372.gannet.ResponseStream
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.benchmarks.arena.JsonPage
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
import java.io.OutputStream
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

    /** HttpArena's fifty-item `/json` page, encoded into a buffer the exchange keeps, as Gannet's carrier does. */
    private val jsonHandler: GannetHandler = api(
        listOf(endpoint { get("json"); json<JsonPage>() } handledNow { EncodeBenchmark.page(FIFTY) }),
        JacksonCodecs,
    ).toHandler(Handlers.onDispatcher)

    private val jsonRequest: HttpRequest = HttpRequest::class.java.constructors.single().newInstance(
        "GET", "/json", "/json", null, HttpVersion.HTTP_1_1, Headers(listOf("Host" to "localhost:8080")), NoBody,
    ) as HttpRequest

    private val onCarrier = Answered(jsonRequest)
    private val offCarrier = Answered(jsonRequest, onCarrier = false)

    /** Spec 0090: on the carrier, straight into Gannet's buffer through the writer `respond`. */
    @Benchmark
    fun json(): Int {
        jsonHandler.handle(onCarrier)
        return onCarrier.size
    }

    /** The same page from another thread, gathered into one array first: the path before Gannet took a writer. */
    @Benchmark
    fun jsonGathered(): Int {
        jsonHandler.handle(offCarrier)
        return offCarrier.size
    }

    private class Answered(override val request: HttpRequest, override val onCarrier: Boolean = true) : HttpExchange {
        var status = 0
        var size = 0
        private val buffer = Kept()

        override fun respond(status: Int, headers: List<Pair<String, String>>, body: ByteArray) {
            this.status = status
            size = body.size
        }

        override fun respond(status: Int, headers: List<Pair<String, String>>, body: (OutputStream) -> Unit) {
            this.status = status
            buffer.size = 0
            body(buffer)
            size = buffer.size
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

    /** A buffer kept between replies, as a carrier keeps its own. */
    private class Kept : OutputStream() {
        private val bytes = ByteArray(1 shl 20)
        var size = 0

        override fun write(b: Int) {
            bytes[size++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            System.arraycopy(b, off, bytes, size, len)
            size += len
        }
    }

    private companion object {
        const val FIFTY = 50
    }
}
