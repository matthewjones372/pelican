package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * The limit is a number of bytes, and the reason it cannot be a number of
 * characters is that a caller chooses the characters.
 */
class StrictBodyTest {

    private fun read(text: String, limit: Long) =
        readStrictBody(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)), limit)

    @Test
    fun `a body inside the limit comes back whole`() {
        read("{\"a\":1}", limit = 64) shouldBe "{\"a\":1}"
    }

    @Test
    fun `a body over the limit is refused`() {
        shouldThrow<PayloadTooLarge> { read("0123456789", limit = 4) }.limit shouldBe 4L
    }

    @Test
    fun `the limit counts bytes, not characters`() {
        // Ten characters, thirty bytes: a character count would let this pass.
        val cjk = "五五五五五五五五五五"
        cjk.length shouldBe 10
        shouldThrow<PayloadTooLarge> { read(cjk, limit = 12) }
    }

    @Test
    fun `multi-byte characters survive the read when they fit`() {
        read("五五", limit = 64) shouldBe "五五"
    }

    @Test
    fun `an empty body is empty text rather than a refusal`() {
        read("", limit = 8) shouldBe ""
    }
}
