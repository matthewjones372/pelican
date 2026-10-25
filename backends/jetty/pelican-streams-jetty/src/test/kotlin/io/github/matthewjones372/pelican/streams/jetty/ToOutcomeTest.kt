package io.github.matthewjones372.pelican.streams.jetty

import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.jetty.handledByOrFail
import io.github.matthewjones372.pelican.ndjsonIn
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.test.frames
import io.github.matthewjones372.pelican.test.jetty.inMemory
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.mapOrFail
import io.github.matthewjones372.surefold.stream.run
import io.github.matthewjones372.surefold.stream.runFold
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
