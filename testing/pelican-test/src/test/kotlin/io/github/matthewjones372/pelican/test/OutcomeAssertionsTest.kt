package io.github.matthewjones372.pelican.test

import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.json
import io.github.matthewjones372.pelican.ok
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

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

    // ------------------------------------------------------ which success

    private val remembered = json<String>(status = 200)
    private val learned = json<String>(status = 201)

    @Test
    fun `shouldBeResponse tells two successes of the same payload type apart`() {
        // The same case again, on the other side: both responses carry a
        // String, and only the declaration the handler named says which is
        // which.
        val created: Outcome<ApiError, String> = learned("Pekko")

        (created shouldBeResponse learned) shouldBe "Pekko"

        val message = messageOfFailing { created shouldBeResponse remembered }

        message shouldContain "json:200"
        message shouldContain "json:201"
    }

    @Test
    fun `shouldBeResponse on a failure says which failure it got instead`() {
        messageOfFailing { missing shouldBeResponse remembered } shouldContain "404"
    }

    /** `ok(...)` names none, and a test asking which one got a straight answer. */
    @Test
    fun `shouldBeResponse on an unnamed success says nothing was named`() {
        messageOfFailing { found shouldBeResponse remembered } shouldContain "declared as (none)"
    }
}
