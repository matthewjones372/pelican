package io.github.matthewjones372.pelican.benchmarks

import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.kotlinx.KotlinxCodecs
import io.github.matthewjones372.pelican.kotlinx.defaultJson
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToStream
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
import org.openjdk.jmh.annotations.Warmup
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.reflect.typeOf

/**
 * What writing one response body costs, as [DecodeBenchmark] measures reading one (spec 0085).
 *
 * The String rows are what both backends did before `encodeTo`: the codec's String, then its UTF-8
 * copy. Run with `-prof gc` (the `jmh` task passes it): `gc.alloc.rate.norm` is the copy.
 *
 * `KotlinxCodecs` keeps the default `encodeTo`, so its bytes row is the override it would have,
 * `encodeToStream`, kept here as the evidence for not having it.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, jvmArgsAppend = ["-Xms512m", "-Xmx512m"])
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
open class EncodeBenchmark {

    @Serializable
    data class Payload(val seq: Long, val body: String)

    /** The sizes 0039 and [DecodeBenchmark] used. */
    @Param("100", "1024", "65536")
    var size: Int = 0

    private lateinit var jackson: BodyCodec<Payload>
    private lateinit var kotlinx: BodyCodec<Payload>
    private lateinit var payload: Payload
    private val json = defaultJson()

    @Setup
    fun setUp() {
        jackson = JacksonCodecs.codec(typeOf<Payload>())
        kotlinx = KotlinxCodecs.codec(typeOf<Payload>())
        payload = Payload(seq = 1, body = "x".repeat(size))
    }

    @Benchmark
    fun viaString(): ByteArray = jackson.encodeToString(payload).toByteArray()

    @Benchmark
    fun viaBytes(): ByteArray = jackson.encodeTo(payload)

    @Benchmark
    fun kotlinxViaString(): ByteArray = kotlinx.encodeToString(payload).toByteArray()

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun kotlinxViaBytes(): ByteArray =
        ByteArrayOutputStream().also { json.encodeToStream(Payload.serializer(), payload, it) }.toByteArray()
}
