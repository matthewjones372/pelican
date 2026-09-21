package io.github.matthewjones372.pelican.jackson

import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.databind.ObjectWriter
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.typeOf

/**
 * What resolving a codec costs, asked once per type rather than once per
 * request. See spec 0046: the encode side already cached its writer and the
 * decode side cached nothing, which looked like an oversight rather than a
 * decision.
 */
class CodecResolutionTest {

    data class Order(val id: Long, val item: String)

    /** Counts what the codec asks of the mapper, so "once" is a number rather than a claim. */
    private class CountingMapper : ObjectMapper() {
        val writers = AtomicInteger()
        val readers = AtomicInteger()

        override fun writerFor(type: JavaType): ObjectWriter =
            super.writerFor(type).also { writers.incrementAndGet() }

        override fun readerFor(type: JavaType): ObjectReader =
            super.readerFor(type).also { readers.incrementAndGet() }
    }

    @Test
    fun `a reader and a writer are built per codec, not per call`() {
        val mapper = CountingMapper().apply { findAndRegisterModules() }
        val codec = JacksonCodecs(mapper).codec<Order>(typeOf<Order>())

        repeat(TRIPS) {
            codec.decodeFromString(codec.encodeToString(Order(it.toLong(), "a-widget")))
        }

        withClue("the writer was already resolved once; the reader is the half that was not") {
            mapper.readers.get() shouldBe 1
        }
        mapper.writers.get() shouldBe 1
    }

    @Test
    fun `a round trip still says what it said before`() {
        val codec = JacksonCodecs.codec<Order>(typeOf<Order>())
        val order = Order(7, "a-widget")

        codec.decodeFromString(codec.encodeToString(order)) shouldBe order
    }

    private companion object {
        /** Enough that a per-call resolution could not hide behind one. */
        const val TRIPS = 5
    }
}
