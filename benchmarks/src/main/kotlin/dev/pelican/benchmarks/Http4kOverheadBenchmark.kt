package dev.pelican.benchmarks

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import dev.pelican.Api
import dev.pelican.IntCodec
import dev.pelican.default
import dev.pelican.div
import dev.pelican.endpoint
import dev.pelican.http4k.handledNow
import dev.pelican.http4k.toHttpHandler
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.noInputs
import dev.pelican.pathParam
import dev.pelican.queryParam
import org.http4k.core.HttpHandler
import org.http4k.core.MemoryBody
import org.http4k.core.MemoryResponse
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit

/**
 * What the description costs, measured rather than asserted.
 *
 * The same endpoint twice: once described with Pelican and interpreted onto
 * http4k, once written directly against http4k's own routing. Both decode a
 * path parameter and an optional query parameter with a default, and both
 * encode the same object with the same Jackson mapper — so what is left in the
 * difference is the interpreter: matching the description, decoding through
 * the declared codecs, and choosing the output shape.
 *
 * In-memory, so no socket, no connection handling and no OS scheduling noise
 * are in the number. That flatters both sides equally and isolates the layer
 * under test.
 *
 * [Mode.AverageTime] in nanoseconds, because the question this answers is
 * "what does one request cost", and a cost is a number you can add to the
 * database call underneath it. Throughput would answer the same question in a
 * unit nobody can add anything to.
 *
 * Three forks and five one-second iterations either side of the warmup: enough
 * that most rows close to a few per cent, few enough that the whole run is
 * under ten minutes and somebody will actually run it. Forks are what catch a
 * JIT decision that happened to go one way in one JVM, and there are three
 * rather than two because one of these benchmarks — the idiomatic http4k route
 * — genuinely lands in one of two modes about a microsecond apart. Two forks
 * reported whichever mode they both happened to hit; three say there are two.
 *
 * The heap is pinned rather than left to the default, which is a quarter of
 * the machine's RAM. These handlers allocate three or four gigabytes a second
 * and never keep any of it, so with a six-gigabyte ceiling the young
 * generation grows until its pages stop being resident — and on a workstation
 * with an IDE open the benchmark then measures the paging, in swings of five
 * and ten times. Half a gigabyte is far more than any of this needs, it is the
 * same on every machine, and it took a run reporting 491µs for a 14µs
 * operation to work that out.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, jvmArgsAppend = ["-Xms512m", "-Xmx512m"])
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
open class Http4kOverheadBenchmark {

    data class Item(val id: Long, val name: String)

    private val itemId = pathParam<Long>("itemId")
    private val limit = queryParam("limit", IntCodec).default(10)

    private val getItem = endpoint(itemId, limit) {
        get("items" / itemId)
        json<Item>()
    }

    private val described: HttpHandler = Api(
        endpoints = listOf(getItem handledNow { (id, lim) -> Item(id, "item-$lim") }),
        codecs = JacksonCodecs,
    ).toHttpHandler()

    /** The same behaviour, written the way an http4k service would write it. */
    private val handWritten: HttpHandler = run {
        val mapper = ObjectMapper().registerKotlinModule()
        routes(
            "/items/{itemId}" bind Method.GET to { req: Request ->
                val id = checkNotNull(req.path("itemId")).toLong()
                val lim = req.query("limit")?.toInt() ?: 10
                Response(Status.OK)
                    .header("Content-Type", "application/json")
                    .body(mapper.writeValueAsString(Item(id, "item-$lim")))
            },
        )
    }

    /**
     * The same again, but with the response built in one construction instead
     * of `Response(...).header(...).body(...)`.
     *
     * http4k's Response is immutable, so the idiomatic chain copies it at each
     * step. Pelican's interpreter builds it in one go, and comparing only
     * against the idiomatic form would credit the library with a trick anyone
     * can use. This is the harder baseline.
     */
    private val handWrittenTuned: HttpHandler = run {
        val mapper = ObjectMapper().registerKotlinModule()
        val contentType = listOf("Content-Type" to "application/json")
        routes(
            "/items/{itemId}" bind Method.GET to { req: Request ->
                val id = checkNotNull(req.path("itemId")).toLong()
                val lim = req.query("limit")?.toInt() ?: 10
                MemoryResponse(
                    Status.OK,
                    contentType,
                    MemoryBody(mapper.writeValueAsString(Item(id, "item-$lim"))),
                )
            },
        )
    }

    // ---------------------------------------------------------------- minimal
    //
    // No inputs, no codec: what the interpreter costs before any decoding.

    private val ping = endpoint(noInputs) {
        get("ping")
        text()
    }

    private val describedPing: HttpHandler = Api(listOf(ping handledNow { "pong" })).toHttpHandler()

    private val handWrittenPing: HttpHandler = routes(
        "/ping" bind Method.GET to { _: Request -> Response(Status.OK).body("pong") },
    )

    // Built once and reused, so that constructing a `Request` is not part of
    // what is being timed. A real server parses one off a socket per request,
    // and that cost is identical on both sides of every comparison here.
    private val request = Request(Method.GET, "/items/7?limit=25")
    private val pingRequest = Request(Method.GET, "/ping")

    /**
     * That every handler actually answers, checked before anything is timed.
     *
     * A route that fails to match returns 404 without decoding, encoding or
     * allocating a body, and would report as a spectacular improvement. This
     * is the difference between a benchmark and a benchmark of nothing.
     */
    @Setup
    fun verify() {
        val interpreted = described(request).bodyString()
        val idiomatic = handWritten(request).bodyString()
        val tuned = handWrittenTuned(request).bodyString()
        check(interpreted == idiomatic && interpreted == tuned) {
            "the three handlers disagree: $interpreted / $idiomatic / $tuned"
        }
        // Both inputs have to have reached the handler for this to hold: the
        // path parameter is the id, the query parameter is in the name.
        check(interpreted == """{"id":7,"name":"item-25"}""") { "unexpected body: $interpreted" }
        val pong = describedPing(pingRequest).bodyString()
        check(pong == handWrittenPing(pingRequest).bodyString() && pong == "pong") { "unexpected body: $pong" }
    }

    // The returned `Response` is what stops the JIT deleting the call. JMH
    // consumes whatever a benchmark method returns, which is a blackhole
    // without the ceremony of taking one as a parameter — and it cannot be got
    // wrong later by someone changing the body to ignore the result.

    @Benchmark
    fun pelican(): Response = described(request)

    @Benchmark
    fun http4kIdiomatic(): Response = handWritten(request)

    @Benchmark
    fun http4kTuned(): Response = handWrittenTuned(request)

    @Benchmark
    fun pelicanNoInputs(): Response = describedPing(pingRequest)

    @Benchmark
    fun http4kNoInputs(): Response = handWrittenPing(pingRequest)
}
