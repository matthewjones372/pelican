package io.github.matthewjones372.pelican.benchmarks

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.pekko.toRoute
import org.apache.pekko.Done
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.http.javadsl.model.ContentTypes
import org.apache.pekko.http.javadsl.model.HttpEntities
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.model.HttpResponse
import org.apache.pekko.http.javadsl.model.StatusCodes
import org.apache.pekko.http.javadsl.server.Directives
import org.apache.pekko.http.javadsl.server.Route
import org.apache.pekko.japi.function.Function
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import org.apache.pekko.util.ByteString
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import io.github.matthewjones372.pelican.pekko.streamedNow as streamedNowOnPekko

/**
 * What the description costs on a streamed response.
 *
 * `PekkoOverheadBenchmark` answers the same question for one value. Streaming
 * is the case where an interpreter could plausibly cost something per
 * *element* rather than per request, so it is asked separately: both rows send
 * one request and drain every frame of the answer.
 *
 * The control is what a Pekko service would write by hand for NDJSON — the
 * source mapped to `ByteString` and handed to `createChunked`, which is also
 * what Pelican does once the description has been read. So the difference is
 * the interpreter and the codec lookup, not the framing strategy.
 *
 * Sealed rather than bound, as everywhere else here: no socket in the number.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Fork(value = 3, jvmArgsAppend = ["-Xms512m", "-Xmx512m"])
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Suppress("ForbiddenVoid") // `Behaviors.empty<Void>()` is Pekko's Java DSL; see config/detekt/detekt.yml.
open class StreamingOverheadBenchmark {

    // `PekkoOverheadBenchmark` keeps its own copy of this, private to its file.
    private typealias Sealed = Function<HttpRequest, CompletionStage<HttpResponse>>

    data class Item(val id: Long, val name: String)

    @Param("100", "10000")
    var elements: Int = 0

    private val system = ActorSystem.create(Behaviors.empty<Void>(), "pelican-streaming-benchmark")

    private val mapper = ObjectMapper().registerKotlinModule()

    private val request: HttpRequest = HttpRequest.GET("/items")

    private fun items(): Source<Item, NotUsed> =
        Source.range(1, elements).map { Item(it.toLong(), "item") }

    private val streamItems = endpoint {
        get("items")
        ndjson<Item>()
    }

    private val described: Sealed by lazy {
        api(
            endpoints = listOf(streamItems streamedNowOnPekko { items() }),
            codecs = JacksonCodecs,
        ).toRoute(system).function(system)
    }

    /**
     * The same frames, written straight against Pekko. The content type is
     * `application/json` rather than `application/x-ndjson` because a header
     * is not what is being measured and registering a media type is not what
     * a reader is here to see.
     */
    private val handWritten: Sealed by lazy {
        val route: Route = Directives.get {
            Directives.path("items") {
                Directives.complete(
                    HttpResponse.create()
                        .withStatus(StatusCodes.OK)
                        .withEntity(
                            HttpEntities.createChunked(
                                ContentTypes.APPLICATION_JSON,
                                items().map { ByteString.fromString(mapper.writeValueAsString(it) + "\n") },
                            ),
                        ),
                )
            }
        }
        route.function(system)
    }

    @Benchmark
    fun describedStream(): Done = drain(described)

    @Benchmark
    fun handWrittenStream(): Done = drain(handWritten)

    /** Every frame consumed, so the source actually runs to completion. */
    private fun drain(route: Sealed): Done {
        val response = route.apply(request).toCompletableFuture().join()
        return response.entity().dataBytes.runWith(Sink.ignore(), system).toCompletableFuture().join()
    }

    @TearDown
    fun tearDown() {
        system.terminate()
        system.whenTerminated.toCompletableFuture().join()
    }
}
