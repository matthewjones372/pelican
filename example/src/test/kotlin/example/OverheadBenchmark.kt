package example

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
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

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
 * Not a JMH harness: no forks, no blackholes, one JVM. Treat the ratio as
 * sound and the absolute numbers as indicative. Skipped unless asked for by
 * name, because a benchmark in a test suite is a slow test.
 */
@EnabledIfSystemProperty(named = "benchmark", matches = "true", disabledReason = "run with -Dbenchmark=true")
class OverheadBenchmark {

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
                val id = req.path("itemId")!!.toLong()
                val lim = req.query("limit")?.toInt() ?: 10
                Response(Status.OK)
                    .header("Content-Type", "application/json")
                    .body(mapper.writeValueAsString(Item(id, "item-$lim")))
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
                val id = req.path("itemId")!!.toLong()
                val lim = req.query("limit")?.toInt() ?: 10
                org.http4k.core.MemoryResponse(
                    Status.OK,
                    contentType,
                    org.http4k.core.MemoryBody(mapper.writeValueAsString(Item(id, "item-$lim"))),
                )
            },
        )
    }

    private fun measure(handler: HttpHandler, requests: Int, path: String = "/items/7?limit=25"): Double {
        val request = Request(Method.GET, path)
        var sink = 0
        val start = System.nanoTime()
        repeat(requests) { sink += handler(request).status.code }
        val elapsed = System.nanoTime() - start
        check(sink == requests * 200) { "a request failed" }
        return elapsed.toDouble() / requests
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    /**
     * Bytes allocated per request, which unlike a timing is the same number
     * every run. Allocation is most of what an abstraction costs at this
     * scale, and it can be compared without a stopwatch.
     */
    private fun bytesPerRequest(handler: HttpHandler, path: String, requests: Int = 400_000): Long {
        val thread = java.lang.management.ManagementFactory.getThreadMXBean()
            as com.sun.management.ThreadMXBean
        val request = Request(Method.GET, path)
        repeat(requests) { handler(request) } // let any one-off allocation happen first
        val id = Thread.currentThread().threadId()
        val before = thread.getThreadAllocatedBytes(id)
        repeat(requests) { handler(request) }
        val after = thread.getThreadAllocatedBytes(id)
        return (after - before) / requests
    }

    @Test
    fun `what the description costs per request`() {
        // Both answer the same thing before either is timed.
        val a = described(Request(Method.GET, "/items/7?limit=25")).bodyString()
        val b = handWritten(Request(Method.GET, "/items/7?limit=25")).bodyString()
        check(a == b) { "the two handlers disagree: $a vs $b" }
        println("both return: $a")

        // Warm both to the same point before either is measured.
        repeat(300_000) { described(Request(Method.GET, "/items/7?limit=25")) }
        repeat(300_000) { handWritten(Request(Method.GET, "/items/7?limit=25")) }

        // Interleaved, so drift in the machine lands on both alike, and
        // compared on medians rather than on any single run.
        val raw = mutableListOf<Double>()
        val pelican = mutableListOf<Double>()
        repeat(9) {
            raw += measure(handWritten, 100_000)
            pelican += measure(described, 100_000)
        }

        // The same, for an endpoint with nothing to decode.
        repeat(300_000) { describedPing(Request(Method.GET, "/ping")) }
        repeat(300_000) { handWrittenPing(Request(Method.GET, "/ping")) }
        val rawPing = mutableListOf<Double>()
        val pelicanPing = mutableListOf<Double>()
        repeat(9) {
            rawPing += measure(handWrittenPing, 100_000, "/ping")
            pelicanPing += measure(describedPing, 100_000, "/ping")
        }

        val rawMedian = median(raw)
        val pelicanMedian = median(pelican)
        println("http4k raw   %8.0f ns/op  (%.0f..%.0f)".format(rawMedian, raw.min(), raw.max()))
        println("pelican      %8.0f ns/op  (%.0f..%.0f)".format(pelicanMedian, pelican.min(), pelican.max()))
        println("difference   %8.0f ns/op  %.2fx".format(pelicanMedian - rawMedian, pelicanMedian / rawMedian))

        repeat(300_000) { handWrittenTuned(Request(Method.GET, "/items/7?limit=25")) }
        val tuned = mutableListOf<Double>()
        repeat(9) { tuned += measure(handWrittenTuned, 100_000) }
        val tunedMedian = median(tuned)
        println("http4k tuned %8.0f ns/op  (one-construction response)".format(tunedMedian))
        println("vs tuned     %8.0f ns/op  %.2fx".format(pelicanMedian - tunedMedian, pelicanMedian / tunedMedian))

        println("-- bytes allocated per request")
        println("http4k tuned %6d B  (json, 2 inputs)".format(bytesPerRequest(handWrittenTuned, "/items/7?limit=25")))
        println("http4k raw   %6d B  (json, 2 inputs)".format(bytesPerRequest(handWritten, "/items/7?limit=25")))
        println("pelican      %6d B  (json, 2 inputs)".format(bytesPerRequest(described, "/items/7?limit=25")))
        println("http4k raw   %6d B  (no inputs)".format(bytesPerRequest(handWrittenPing, "/ping")))
        println("pelican      %6d B  (no inputs)".format(bytesPerRequest(describedPing, "/ping")))

        val rawPingMedian = median(rawPing)
        val pelicanPingMedian = median(pelicanPing)
        println("-- no inputs, text out")
        println("http4k raw   %8.0f ns/op".format(rawPingMedian))
        println("pelican      %8.0f ns/op".format(pelicanPingMedian))
        println(
            "difference   %8.0f ns/op  %.2fx".format(
                pelicanPingMedian - rawPingMedian,
                pelicanPingMedian / rawPingMedian,
            ),
        )
    }
}
