package io.github.matthewjones372.pelican.benchmarks

import com.fasterxml.jackson.core.json.JsonWriteFeature
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.BodySink
import io.github.matthewjones372.pelican.benchmarks.arena.JsonPage
import io.github.matthewjones372.pelican.benchmarks.arena.PricedItem
import io.github.matthewjones372.pelican.benchmarks.arena.Rating
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jackson.defaultMapper
import io.github.matthewjones372.pelican.spi.encodedBytes
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
 * Spec 0090's `encodeTo` against the String it replaces, on HttpArena's `/json` page: the bytes a backend is
 * handed, one array either way, as Gannet's `respond` takes them. Run with `-prof gc`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(value = 3, jvmArgsAppend = ["-Xms512m", "-Xmx512m"])
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
open class EncodeBenchmark {

    /** One item, and the fifty HttpArena's `json` profile asks for. */
    @Param("1", "50")
    var items: Int = 0

    private lateinit var codec: BodyCodec<JsonPage>
    private lateinit var page: JsonPage

    @Setup
    fun setUp() {
        codec = JacksonCodecs.codec(typeOf<JsonPage>())
        page = page(items)
    }

    /** Before: Jackson's chars to a String, then the String to UTF-8. */
    @Benchmark
    fun viaString(): ByteArray = codec.encodeToString(page).toByteArray()

    /** After: `encodeTo` gathered into one array, as every backend now answers a JSON payload. */
    @Benchmark
    fun encodeTo(): ByteArray = codec.encodedBytes(page)

    /** Jackson's own bytes, through its recycled segments: what an exact array costs at best. */
    @Benchmark
    fun viaJacksonBytes(): ByteArray = writer.writeValueAsBytes(page)

    /** Into a buffer already there, as a backend's output buffer would be: what `encodeTo` is for. */
    @Benchmark
    fun intoBuffer(): Int {
        buffer.reset()
        codec.encodeTo(page, buffer)
        return buffer.size
    }

    private val writer by lazy {
        defaultMapper().writerFor(defaultMapper().constructType(JsonPage::class.java))
            .with(JsonWriteFeature.COMBINE_UNICODE_SURROGATES_IN_UTF8)
    }
    private val buffer = Reused()

    /** A buffer kept between calls, as a connection keeps its own. */
    private class Reused : BodySink {
        private val bytes = ByteArray(1 shl 20)
        var size = 0
            private set

        fun reset() {
            size = 0
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            System.arraycopy(bytes, offset, this.bytes, size, length)
            size += length
        }
    }

    companion object {
        /** [items] made-up items in HttpArena's shape, so no dataset file is read. */
        fun page(items: Int): JsonPage {
            val all = (1..items).map {
                PricedItem(
                    it.toLong(), "Item $it", "category-${it % 7}", it * 100L, it * 3L, it % 2 == 0,
                    listOf("tag-a", "tag-b"), Rating(it % 5, it * 11), it * 300L,
                )
            }
            return JsonPage(all, all.size)
        }
    }
}
