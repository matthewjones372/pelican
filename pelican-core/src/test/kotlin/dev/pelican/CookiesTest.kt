package dev.pelican

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The `Cookie` header, read and written in core so that the three backends
 * cannot come to different conclusions about the same request.
 */
class CookiesTest {

    @Test
    fun `a jar is split on semicolons, and the surrounding space is not part of the value`() {
        assertEquals(
            mapOf("locale" to "de", "session" to "xyz", "_ga" to "GA1.2.3"),
            Cookies.parse("locale=de; session=xyz; _ga=GA1.2.3"),
        )
    }

    @Test
    fun `a value keeps everything a cookie value may legally contain`() {
        // `=` is legal inside a value, and a base64 credential ends in them
        // often enough that splitting on the last one would be a bug people hit.
        assertEquals(mapOf("token" to "YWJjZA=="), Cookies.parse("token=YWJjZA=="))
    }

    @Test
    fun `a quoted value is unquoted, because plenty of servers write one`() {
        assertEquals(mapOf("session" to "xyz"), Cookies.parse("""session="xyz""""))
    }

    @Test
    fun `the first spelling of a name wins, across headers as well as within one`() {
        // RFC 6265 orders the more specific cookie first, so a later duplicate
        // is the one to drop.
        assertEquals("first", Cookies.parse(listOf("a=first; a=second"))["a"])
        assertEquals("first", Cookies.parse(listOf("a=first", "a=second"))["a"])
    }

    @Test
    fun `something that is not a pair at all is skipped rather than fatal`() {
        val cookies = Cookies.parse("; =nameless; lonely; locale=de")

        assertEquals(mapOf("locale" to "de"), cookies)
        assertNull(cookies["lonely"])
    }

    @Test
    fun `no header at all is no cookies`() {
        assertEquals(emptyMap<String, String>(), Cookies.parse(null))
    }

    @Test
    fun `rendering produces the header a browser would send`() {
        assertEquals("locale=de; session=xyz", Cookies.render(listOf("locale" to "de", "session" to "xyz")))
    }

    @Test
    fun `a value a cookie cannot carry is refused where it is written`() {
        // Sending it would produce a header the far end reads as two cookies,
        // or as one with a truncated value. Failing here is the only place the
        // caller can still do something about it.
        val failure = assertThrows<IllegalArgumentException> {
            Cookies.render(listOf("session" to "a;b"))
        }

        assertTrue("session" in (failure.message ?: ""))
    }
}
