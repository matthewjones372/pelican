package dev.surefold.pelican.streams.jetty

import dev.surefold.pelican.ApiError
import dev.surefold.pelican.Outcome
import dev.surefold.pelican.api
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.errorJson
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.jetty.Handlers
import dev.surefold.pelican.jetty.handledByOrFail
import dev.surefold.pelican.ndjsonIn
import dev.surefold.pelican.orFail
import dev.surefold.pelican.test.frames
import dev.surefold.pelican.test.jetty.inMemory
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.mapOrFail
import dev.surefold.stream.run
import dev.surefold.stream.runFold
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/** All three ways a run can end, as the caller sees them. */
class ToOutcomeTest {

    data class Note(val text: String)

    data class Tally(val counted: Int)

    private val badRows = errorJson<ApiError>(422, "A row could not be read")

    private val tally = endpoint(ndjsonIn<Note>()) {
        post("tally")
        operationId = "tally"
        json<Tally>() orFail badRows
    }

    private fun app(counting: (String) -> String) = api(
        endpoints = listOf(
            tally handledByOrFail { rows ->
                rows.toStream()
                    .mapOrFail<ApiError, Note, String> { note ->
                        counting(note.text).ifEmpty { fail(ApiError(422, "empty row")) }
                    }
                    .runFold(Tally(0)) { seen, _ -> Tally(seen.counted + 1) }
                    .run(Forks())
                    .toOutcome(badRows)
            },
        ),
        codecs = JacksonCodecs,
    ).inMemory(Handlers.onVirtualThreads)

    private fun notes(vararg text: String) = frames(text.map { Note(it) })

    @Test
    fun `a run that finishes is the value the endpoint declared`() {
        app { it }.use { client ->
            client.call(tally, notes("one", "two")) shouldBe Tally(2)
        }
    }

    @Test
    fun `a declared failure comes back as the failure, not as a thrown one`() {
        app { it }.use { client ->
            val answer = client.outcome(tally, notes("fine", "", "never read"))

            answer.shouldBeInstanceOf<Outcome.Err<ApiError>>().error.status shouldBe 422
        }
    }

    /** A 500 is not one of the declared responses, so it is read raw. */
    @Test
    fun `a defect nobody declared is not dressed up as the declared failure`() {
        app { error("the fold blew up on it") }.use { client ->
            client.response(tally, notes("one")).status shouldBe 500
        }
    }

    @Test
    fun `Done is the value`() {
        val converted = CompletableFuture.completedFuture<Exit<ApiError, Tally>>(Exit.Done(Tally(7)))

        converted.toOutcome(badRows).toCompletableFuture().join() shouldBe Outcome.Ok(Tally(7))
    }

    @Test
    fun `Failed is the declared failure, which is what fixes the status`() {
        val refused = ApiError(422, "no")
        val converted = CompletableFuture.completedFuture<Exit<ApiError, Tally>>(Exit.Failed(refused))

        val answer = converted.toOutcome(badRows).toCompletableFuture().join()

        answer.shouldBeInstanceOf<Outcome.Err<ApiError>>().error shouldBe refused
    }

    @Test
    fun `Died is rethrown as itself, not folded into the declared failure`() {
        val broke = IllegalStateException("the fold blew up")
        val converted = CompletableFuture.completedFuture<Exit<ApiError, Tally>>(Exit.Died(broke))

        val thrown = shouldThrow<Throwable> { converted.toOutcome(badRows).toCompletableFuture().join() }

        generateSequence(thrown) { it.cause }.toList() shouldContain broke
    }
}
