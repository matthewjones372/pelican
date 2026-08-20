package dev.pelican.test

import dev.pelican.ApiError
import dev.pelican.Outcome
import dev.pelican.errorJson
import dev.pelican.ok
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What an assertion on an `Outcome` should read like, and what it should say
 * when it fails.
 *
 * The `when` this replaces had to spell out a branch that must not happen and
 * put an `error("...")` in it, which is three lines of scaffolding around one
 * line of assertion — and the failure message was whatever the test author
 * happened to type there.
 */
class OutcomeAssertionsTest {

    private data class NoSuchBookmark(val id: Long, val message: String)

    private val bookmarkMissing = errorJson<NoSuchBookmark>(404, "No bookmark with that id")
    private val badKey = errorJson<ApiError>(401, "Bad key")
    private val forbidden = errorJson<ApiError>(403, "Forbidden")

    private val found: Outcome<NoSuchBookmark, String> = ok("Pekko")
    private val missing: Outcome<NoSuchBookmark, String> =
        bookmarkMissing(NoSuchBookmark(9_999L, "No bookmark 9999"))

    // ------------------------------------------------------------------ ok

    @Test
    fun `shouldBeOk returns the value, so the next assertion is about the payload`() {
        assertEquals("Pekko", found.shouldBeOk())
    }

    @Test
    fun `shouldBeOk on a failure says which failure it got`() {
        val failure = assertThrows<AssertionError> { missing.shouldBeOk() }
        assertTrue(failure.message!!.contains("404"), failure.message!!)
        assertTrue(failure.message!!.contains("No bookmark 9999"), failure.message!!)
    }

    // --------------------------------------------------------------- error

    @Test
    fun `shouldBeError returns the payload, for chaining onto your own matchers`() {
        val error: NoSuchBookmark = missing.shouldBeError()
        assertEquals(9_999L, error.id)
    }

    @Test
    fun `shouldBeError with an expected value compares it`() {
        missing shouldBeError NoSuchBookmark(9_999L, "No bookmark 9999")
    }

    @Test
    fun `shouldBeError prints both sides when the payload differs`() {
        val failure = assertThrows<AssertionError> {
            missing shouldBeError NoSuchBookmark(1L, "wrong")
        }
        assertTrue(failure.message!!.contains("id=1"), failure.message!!)
        assertTrue(failure.message!!.contains("id=9999"), failure.message!!)
    }

    @Test
    fun `shouldBeError on a success says what came back instead`() {
        val failure = assertThrows<AssertionError> { found.shouldBeError() }
        assertTrue(failure.message!!.contains("succeeded with: Pekko"), failure.message!!)
    }

    // ----------------------------------------------------- which declaration

    @Test
    fun `shouldBeFailure tells two failures of the same payload type apart`() {
        // The case equality cannot decide: both carry ApiError, and only the
        // declaration the handler named says which one this is.
        val payload = ApiError(401, "Nope")
        val unauthorized: Outcome<ApiError, String> = badKey(payload)

        unauthorized shouldBeFailure badKey

        val failure = assertThrows<AssertionError> { unauthorized shouldBeFailure forbidden }
        assertTrue(failure.message!!.contains("error:403"), failure.message!!)
        assertTrue(failure.message!!.contains("error:401"), failure.message!!)
    }

    @Test
    fun `shouldBeFailure on a success says so`() {
        val ok: Outcome<ApiError, String> = ok("fine")
        val failure = assertThrows<AssertionError> { ok shouldBeFailure badKey }
        assertTrue(failure.message!!.contains("succeeded with: fine"), failure.message!!)
    }
}
