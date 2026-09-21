package io.github.matthewjones372.pelican.benchmarks

import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jackson.defaultMapper
import org.apache.pekko.util.ByteString
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
import java.util.concurrent.TimeUnit
import kotlin.reflect.typeOf

/**
 * What reading one request body costs, which 0039 excluded by name when it
 * measured the writing half.
 *
 * Both rows start from a `ByteString`, because that is what the interpreter
 * holds: `Interpreter.kt` takes a strict entity and its `data` is bytes. The
 * String row is today's path — `utf8String()` and then the codec — so the
 * UTF-16 materialisation being questioned is inside the measurement rather
 * than set up before it. The bytes row hands Jackson the bytes Pekko already
 * had, which is `UTF8StreamJsonParser` instead of the char-based reader.
 *
 * Both use a codec whose reader is resolved once, which is what
 * `JacksonCodecs` does. Measuring a cached reader against an uncached one
 * would be measuring the entry before this one a second time.
 *
 * No socket and no route, so the difference is not hidden inside Pekko. Run
 * with `-prof gc` (the `jmh` task passes it): `gc.alloc.rate.norm` is half the
 * answer, and on the encode side it was the half that moved.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, jvmArgsAppend = ["-Xms512m", "-Xmx512m"])
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
open class DecodeBenchmark {

    data class Payload(val seq: Long, val body: String)

    /** The sizes 0039 used, so the two specs can be read against each other. */
    @Param("100", "1024", "65536")
    var size: Int = 0

    private lateinit var codec: BodyCodec<Payload>
    private lateinit var body: ByteString
    private val mapper = defaultMapper()
    private lateinit var javaType: com.fasterxml.jackson.databind.JavaType
    private lateinit var reader: com.fasterxml.jackson.databind.ObjectReader

    @Setup
    fun setUp() {
        codec = JacksonCodecs.codec(typeOf<Payload>())
        body = ByteString.fromString(codec.encodeToString(Payload(seq = 1, body = "x".repeat(size))))
        javaType = mapper.constructType(Payload::class.java)
        reader = mapper.readerFor(javaType)
    }

    /** Today: the whole body decoded to UTF-16, then parsed as chars. */
    @Benchmark
    fun viaString(): Payload = codec.decodeFromString(body.utf8String())

    /** The bytes Pekko already had, parsed as UTF-8. */
    @Benchmark
    fun viaBytes(): Payload = reader.readValue(body.toArray())
}
