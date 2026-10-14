package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class JsonSuffixTest {

    data class Problem(val title: String)

    private val json = object : BodyCodec<Problem> {
        override fun encodeToString(value: Problem): String = """{"title":"${value.title}"}"""
        override fun decodeFromString(text: String): Problem = Problem(text)
    }

    /** A JSON library as core sees one: it answers the type, and nothing else. */
    private val codecs = object : CodecFactory {
        @Suppress("UNCHECKED_CAST")
        override fun <T> codec(type: KType): BodyCodec<T> = json as BodyCodec<T>
    }

    private fun writerFor(mediaType: String): BodyCodec<Problem> = codecs.codec(typeOf<Problem>(), mediaType)

    @Test
    fun `a structured suffix of +json is written by the JSON codec`() {
        writerFor("application/problem+json") shouldBeSameInstanceAs json
        writerFor("application/health+json") shouldBeSameInstanceAs json
        writerFor("application/vnd.orders.v2+json") shouldBeSameInstanceAs json
    }

    @Test
    fun `parameters and case do not hide the suffix`() {
        writerFor("application/problem+json; charset=utf-8") shouldBeSameInstanceAs json
        writerFor("Application/Problem+JSON") shouldBeSameInstanceAs json
        writerFor("APPLICATION/JSON") shouldBeSameInstanceAs json
    }

    @Test
    fun `a type that only looks like JSON still needs a writer of its own`() {
        listOf("application/json-seq", "application/jsonp", "text/csv", "application/json+xml").forEach { type ->
            shouldThrow<IllegalStateException> { writerFor(type) }.message shouldContain type
        }
    }
}
