package io.github.matthewjones372.pelican

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * What one path segment says, and what it refuses to say.
 *
 * The claims about *routing* are in `RouteIndexTest`; these are about the
 * decoder alone, because the interesting cases — a lone `%`, a truncated
 * escape — are ones no described path would ever reach.
 */
class PercentDecodingTest {

    private fun decode(raw: String): DecodedSegment = decodeSegment(raw)

    @Test
    fun `a segment with nothing to decode is itself`() {
        decode("orders") shouldBe DecodedSegment.Ok("orders")
    }

    @Test
    fun `a plus is a plus, because a path is not a form`() {
        decode("c++") shouldBe DecodedSegment.Ok("c++")
    }

    @Test
    fun `an encoded plus is the same plus`() {
        decode("c%2B%2B") shouldBe DecodedSegment.Ok("c++")
    }

    @Test
    fun `an encoded space is a space, and an encoded percent is a percent`() {
        decode("ada%20lovelace") shouldBe DecodedSegment.Ok("ada lovelace")
        decode("100%25") shouldBe DecodedSegment.Ok("100%")
    }

    @Test
    fun `an encoded slash is a slash, and the split already happened`() {
        decode("a%2Fb") shouldBe DecodedSegment.Ok("a/b")
    }

    @Test
    fun `an escape spelling an ordinary character is that character`() {
        decode("%61dmin") shouldBe DecodedSegment.Ok("admin")
    }

    @Test
    fun `lower and upper case hex digits mean the same byte`() {
        decode("%2f") shouldBe DecodedSegment.Ok("/")
        decode("%2F") shouldBe DecodedSegment.Ok("/")
    }

    @Test
    fun `several bytes make one character`() {
        decode("%E2%82%AC") shouldBe DecodedSegment.Ok("€")
    }

    @Test
    fun `a non-hex escape is malformed rather than an exception`() {
        decode("%zz") shouldBe DecodedSegment.Malformed
        decode("%2z") shouldBe DecodedSegment.Malformed
    }

    @Test
    fun `an escape the segment ran out of room for is malformed too`() {
        decode("%") shouldBe DecodedSegment.Malformed
        decode("%2") shouldBe DecodedSegment.Malformed
        decode("ada%") shouldBe DecodedSegment.Malformed
    }

    @Test
    fun `decoding happens once, so an encoded escape survives as text`() {
        // `%2561` is `%61` encoded. A second pass would read it as `a`, which is
        // how a path becomes two different paths depending on who decodes it.
        decode("%2561") shouldBe DecodedSegment.Ok("%61")
    }
}
