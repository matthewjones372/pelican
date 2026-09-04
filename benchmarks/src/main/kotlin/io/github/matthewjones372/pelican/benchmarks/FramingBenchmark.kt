package io.github.matthewjones372.pelican.benchmarks

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.NdjsonOutput
import io.github.matthewjones372.pelican.SseOutput
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.reflect.typeOf

/**
 * What one element of a streamed response costs on the way to the socket.
 *
 * Today it is allocated three times: the codec returns a String, the framing
 * concatenates a second, and `ByteString.fromString` encodes UTF-8 into a
 * third. The bytes rows are what a codec writing straight into one buffer
 * would cost instead — Jackson's `writeValue(OutputStream, …)` rather than
 * `writeValueAsString` — and `fromArrayUnsafe`, which wraps without copying.
 *
 * No socket and no route: both rows are the framing alone, so the difference
 * is not hidden inside Pekko. Run with `-prof gc` (the `jmh` task passes it) —
 * `gc.alloc.rate.norm` is the number this exists to produce.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, jvmArgsAppend = ["-Xms512m", "-Xmx512m"])
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
open class FramingBenchmark {

    data class Payload(val seq: Long, val body: String)

    /** Roughly the byte sizes the spec asks about, once encoded. */
    @Param("100", "1024", "65536")
    var size: Int = 0

    private lateinit var value: Payload
    private lateinit var codec: BodyCodec<Payload>
    private lateinit var ndjson: NdjsonOutput<Payload>
    private lateinit var sse: SseOutput<Payload>
    private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule()

    @Setup
    fun setUp() {
        value = Payload(seq = 1, body = "x".repeat(size))
        codec = JacksonCodecs.codec(typeOf<Payload>())

        @Suppress("UNCHECKED_CAST")
        ndjson = endpoint { get("ndjson"); ndjson<Payload>() }.output as NdjsonOutput<Payload>

        @Suppress("UNCHECKED_CAST")
        sse = endpoint { get("sse"); sse<Payload>(eventName = "tick", id = { it.seq.toString() }) }
            .output as SseOutput<Payload>
    }

    @Benchmark
    fun ndjsonViaString(): ByteString = ByteString.fromString(ndjson.frame(codec, value))

    @Benchmark
    fun ndjsonViaBytes(): ByteString {
        val out = ByteArrayOutputStream(size + HEADROOM)
        mapper.writeValue(out, value)
        out.write('\n'.code)
        return ByteString.fromArrayUnsafe(out.toByteArray())
    }

    /** Jackson's own recycled buffers, rather than a synchronized stream. */
    @Benchmark
    fun ndjsonViaRecycledBytes(): ByteString {
        val json = mapper.writeValueAsBytes(value)
        val framed = json.copyOf(json.size + 1)
        framed[json.size] = '\n'.code.toByte()
        return ByteString.fromArrayUnsafe(framed)
    }

    @Benchmark
    fun sseViaString(): ByteString = ByteString.fromString(sse.frame(codec, value))

    @Benchmark
    fun sseViaBytes(): ByteString {
        val out = ByteArrayOutputStream(size + HEADROOM)
        out.write(EVENT)
        out.write(ID)
        out.write(value.seq.toString().toByteArray())
        out.write(NEWLINE)
        out.write(DATA)
        mapper.writeValue(out, value)
        out.write(TERMINATOR)
        return ByteString.fromArrayUnsafe(out.toByteArray())
    }

    @Benchmark
    fun sseViaRecycledBytes(): ByteString {
        val json = mapper.writeValueAsBytes(value)
        val id = value.seq.toString().toByteArray()
        val framed = ByteArray(EVENT.size + ID.size + id.size + 1 + DATA.size + json.size + 2)
        var at = 0
        fun put(bytes: ByteArray) {
            bytes.copyInto(framed, at)
            at += bytes.size
        }
        put(EVENT); put(ID); put(id); put(NEWLINE); put(DATA); put(json); put(TERMINATOR)
        return ByteString.fromArrayUnsafe(framed)
    }

    private companion object {
        /** The envelope and the newline, so the buffer is never grown. */
        const val HEADROOM = 64

        val EVENT = "event: tick\n".toByteArray()
        val ID = "id: ".toByteArray()
        val DATA = "data: ".toByteArray()
        val NEWLINE = "\n".toByteArray()
        val TERMINATOR = "\n\n".toByteArray()
    }
}
