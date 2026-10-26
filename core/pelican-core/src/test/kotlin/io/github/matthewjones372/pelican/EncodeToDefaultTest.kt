package io.github.matthewjones372.pelican

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Spec 0090: a codec written before `encodeTo` writes its String's UTF-8, or the bytes it holds, in one call. */
class EncodeToDefaultTest {

    private class Plain(private val held: ByteArray? = null) : BodyCodec<String> {
        override fun encodeToString(value: String): String = "<$value>"
        override fun decodeFromString(text: String): String = text
        override fun encoded(value: String): ByteArray? = held
    }

    private fun BodyCodec<String>.written(value: String): List<String> {
        val writes = mutableListOf<String>()
        encodeTo(value) { bytes, offset, length -> writes += bytes.decodeToString(offset, offset + length) }
        return writes
    }

    @Test
    fun `the string is written once, as UTF-8`() {
        Plain().written("café") shouldBe listOf("<café>")
    }

    @Test
    fun `bytes the codec already holds are written instead`() {
        Plain("held".toByteArray()).written("ignored") shouldBe listOf("held")
    }
}
