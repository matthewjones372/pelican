package dev.pelican.test

import dev.pelican.ApiError
import dev.pelican.Outcome
import dev.pelican.errorJson
import dev.pelican.ok
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

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

    /** The message these assertions are really about — the reason they exist. */
    private inline fun messageOfFailing(block: () -> Unit): String =
        shouldThrow<AssertionError>(block).message.shouldNotBeNull()

    // ------------------------------------------------------------------ ok

    @Test
    fun `shouldBeOk returns the value, so the next assertion is about the payload`() {
        found.shouldBeOk() shouldBe "Pekko"
    }

    @Test
    fun `shouldBeOk on a failure says which failure it got`() {
        val message = messageOfFailing { missing.shouldBeOk() }

        message shouldContain "404"
        message shouldContain "No bookmark 9999"
    }

    // --------------------------------------------------------------- error

    @Test
    fun `shouldBeError returns the payload, for chaining onto your own matchers`() {
        val error: NoSuchBookmark = missing.shouldBeError()

        error.id shouldBe 9_999L
    }

    @Test
    fun `shouldBeError with an expected value compares it`() {
        missing shouldBeError NoSuchBookmark(9_999L, "No bookmark 9999")
    }

    @Test
    fun `shouldBeError prints both sides when the payload differs`() {
        val message = messageOfFailing { missing shouldBeError NoSuchBookmark(1L, "wrong") }

        message shouldContain "id=1"
        message shouldContain "id=9999"
    }

    @Test
    fun `shouldBeError on a success says what came back instead`() {
        messageOfFailing { found.shouldBeError() } shouldContain "succeeded with: Pekko"
    }

    // ----------------------------------------------------- which declaration

    @Test
    fun `shouldBeFailure tells two failures of the same payload type apart`() {
        // The case equality cannot decide: both carry ApiError, and only the
        // declaration the handler named says which one this is.
        val unauthorized: Outcome<ApiError, String> = badKey(ApiError(401, "Nope"))

        unauthorized shouldBeFailure badKey

        val message = messageOfFailing { unauthorized shouldBeFailure forbidden }

        message shouldContain "error:403"
        message shouldContain "error:401"
    }

    @Test
    fun `shouldBeFailure on a success says so`() {
        val ok: Outcome<ApiError, String> = ok("fine")

        messageOfFailing { ok shouldBeFailure badKey } shouldContain "succeeded with: fine"
    }
}
