package dev.pelican

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * A response header is declared the way an input is, and the bargain is the
 * same in the other direction: what a handler may set is what the document
 * promises, because they are the same value.
 */
class ResponseHeadersTest {

    private val location = responseHeader<String>("Location", "Where it went")
    private val retryAfter = responseHeader<Long>("Retry-After").optional()
    private val undeclared = responseHeader<String>("X-Sneaky")

    private val created = endpoint {
        post("things")
        emits(location, retryAfter)
        empty(status = 201)
    }

    private fun paramsFor(ep: Endpoint<*, *>) = Params(emptyMap(), null, ep)

    @Test
    fun `a declared header is encoded by its own codec`() {
        val p = paramsFor(created)
        p.setHeader(location, "/things/7")
        p.setHeader(retryAfter, 30L)

        assertEquals(listOf("Location" to "/things/7", "Retry-After" to "30"), p.responseHeaders())
    }

    @Test
    fun `setting a header the endpoint never declared throws`() {
        val failure = assertThrows<IllegalStateException> {
            paramsFor(created).setHeader(undeclared, "surprise")
        }
        assertTrue(failure.message!!.contains("never declared it"), failure.message!!)
    }

    @Test
    fun `an undocumented header can still be set deliberately`() {
        val p = paramsFor(created)
        p.setRawHeader("X-Debug-Timing", "12ms")
        assertEquals(listOf("X-Debug-Timing" to "12ms"), p.responseHeaders())
    }

    @Test
    fun `a required header nobody set is reported, not enforced`() {
        val p = paramsFor(created)
        p.setHeader(retryAfter, 1L)

        // `location` is required and missing; `retryAfter` is optional and set.
        assertEquals(listOf(location), p.missingRequiredHeaders())
        // Reporting, not failing: replacing a wrong header with a 500 would be
        // a worse answer than the one the handler produced.
        assertEquals(listOf("Retry-After" to "1"), p.responseHeaders())
    }

    @Test
    fun `setting one twice keeps the last value, in its original position`() {
        val p = paramsFor(created)
        p.setHeader(location, "/things/1")
        p.setHeader(retryAfter, 5L)
        p.setHeader(location, "/things/2")

        assertEquals(listOf("Location" to "/things/2", "Retry-After" to "5"), p.responseHeaders())
    }
}
