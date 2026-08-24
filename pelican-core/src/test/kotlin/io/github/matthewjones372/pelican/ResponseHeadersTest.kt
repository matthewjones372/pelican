package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

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

        p.responseHeaders() shouldBe listOf("Location" to "/things/7", "Retry-After" to "30")
    }

    @Test
    fun `setting a header the endpoint never declared throws`() {
        val failure = shouldThrow<IllegalStateException> {
            paramsFor(created).setHeader(undeclared, "surprise")
        }
        failure.message shouldContain "never declared it"
    }

    @Test
    fun `an undocumented header can still be set deliberately`() {
        val p = paramsFor(created)
        p.setRawHeader("X-Debug-Timing", "12ms")
        p.responseHeaders() shouldBe listOf("X-Debug-Timing" to "12ms")
    }

    @Test
    fun `a required header nobody set is reported, not enforced`() {
        val p = paramsFor(created)
        p.setHeader(retryAfter, 1L)

        // `location` is required and missing; `retryAfter` is optional and set.
        p.missingRequiredHeaders() shouldBe listOf(location)
        // Reporting, not failing: replacing a wrong header with a 500 would be
        // a worse answer than the one the handler produced.
        p.responseHeaders() shouldBe listOf("Retry-After" to "1")
    }

    @Test
    fun `setting one twice keeps the last value, in its original position`() {
        val p = paramsFor(created)
        p.setHeader(location, "/things/1")
        p.setHeader(retryAfter, 5L)
        p.setHeader(location, "/things/2")

        p.responseHeaders() shouldBe listOf("Location" to "/things/2", "Retry-After" to "5")
    }
}
