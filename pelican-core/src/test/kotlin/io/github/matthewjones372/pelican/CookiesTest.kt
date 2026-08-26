package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class CookiesTest {

    @Test
    fun `a jar is split on semicolons, and the surrounding space is not part of the value`() {
        Cookies.parse("locale=de; session=xyz; _ga=GA1.2.3") shouldBe
            mapOf("locale" to "de", "session" to "xyz", "_ga" to "GA1.2.3")
    }

    @Test
    fun `a value keeps everything a cookie value may legally contain`() {
        // `=` is legal inside a value, and a base64 credential ends in them
        // often enough that splitting on the last one would be a bug people hit.
        Cookies.parse("token=YWJjZA==") shouldBe mapOf("token" to "YWJjZA==")
    }

    @Test
    fun `a quoted value is unquoted, because plenty of servers write one`() {
        Cookies.parse("""session="xyz"""") shouldBe mapOf("session" to "xyz")
    }

    @Test
    fun `the first spelling of a name wins, across headers as well as within one`() {
        // RFC 6265 orders the more specific cookie first, so a later duplicate
        // is the one to drop.
        Cookies.parse(listOf("a=first; a=second"))["a"] shouldBe "first"
        Cookies.parse(listOf("a=first", "a=second"))["a"] shouldBe "first"
    }

    @Test
    fun `something that is not a pair at all is skipped rather than fatal`() {
        val cookies = Cookies.parse("; =nameless; lonely; locale=de")

        cookies shouldBe mapOf("locale" to "de")
        cookies["lonely"].shouldBeNull()
    }

    @Test
    fun `no header at all is no cookies`() {
        Cookies.parse(null) shouldBe emptyMap<String, String>()
    }

    @Test
    fun `rendering produces the header a browser would send`() {
        Cookies.render(listOf("locale" to "de", "session" to "xyz")) shouldBe "locale=de; session=xyz"
    }

    @Test
    fun `a value a cookie cannot carry is refused where it is written`() {
        // Sending it would produce a header the far end reads as two cookies,
        // or as one with a truncated value. Failing here is the only place the
        // caller can still do something about it.
        val failure = shouldThrow<IllegalArgumentException> {
            Cookies.render(listOf("session" to "a;b"))
        }

        failure.message shouldContain "session"
    }
}
