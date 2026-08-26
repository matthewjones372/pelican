package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.renderError
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
/**
 * What a filter is told the status is, asked of the description alone.
 *
 * The claim these make is narrow on purpose: the number here is the number the
 * interpreter is about to write. That the three interpreters agree with it is
 * not something this module can prove — it has no server — so it is proved
 * where the servers are, by `MetricsAcrossBackendsTest` in the example, which
 * compares what a filter saw against what came back over the socket.
 */
class ResponseStatusTest {

    private val created = json<Note>(201)
    private val unchanged = json<Note>(200)
    private val missing = errorJson<ApiError>(404, "No note by that id")
    private val throttled = errorJson<ApiError>(429, "Too many notes")

    private data class Note(val text: String)

    private val plain = endpoint {
        get("notes")
        operationId = "listNotes"
        json<List<Note>>()
    }

    private val accepted = endpoint {
        post("notes")
        operationId = "queueNote"
        empty(202)
    }

    private val several = endpoint {
        put("notes")
        operationId = "saveNote"
        (created or unchanged).orFail(missing, throttled)
    }

    @Test
    fun `an endpoint with one declared response answers with the status it declared`() {
        plain.statusFor(listOf(Note("hi")), error = null) shouldBe 200
        accepted.statusFor(Unit, error = null) shouldBe 202
    }

    @Test
    fun `a bare ok names the first declared success`() {
        several.statusFor(ok(Note("hi")), error = null) shouldBe 201
    }

    @Test
    fun `a named success answers with that response's status`() {
        several.statusFor(unchanged(Note("hi")), error = null) shouldBe 200
        several.statusFor(created(Note("hi")), error = null) shouldBe 201
    }

    @Test
    fun `a declared failure takes its status from the declaration, not the payload`() {
        // Both failures carry an ApiError, which is exactly why the payload
        // cannot be what decides: only the declaration separates them.
        several.statusFor(missing(ApiError(404, "gone")), error = null) shouldBe 404
        several.statusFor(throttled(ApiError(429, "slow down")), error = null) shouldBe 429
    }

    @Test
    fun `a throwable is read through the same table the response is rendered from`() {
        listOf<Throwable>(
            ApiException(403, "no"),
            DecodeFailure("id", raw = "seven", expected = "an integer"),
            BodyDecodeFailure("not JSON"),
            NotAcceptable(setOf("application/json")),
            PayloadTooLarge(limit = 10L),
            IllegalStateException("something nobody described"),
        ).forEach { failure ->
            plain.statusFor(result = null, error = failure) shouldBe renderError(failure, api = null).error.status
        }
    }

    @Test
    fun `an unexpected throwable is a 500 without minting a reference for it`() {
        // The reference belongs to the response that prints it; asking for the
        // status alone must not consume one that no log line will ever carry.
        plain.statusFor(result = null, error = IllegalStateException("boom")) shouldBe 500
    }

    @Test
    fun `afterStatus reports the status the endpoint would answer with`() {
        val seen = mutableListOf<Int>()
        val chain = listOf(afterStatus { _, status, _ -> seen += status })
            .wrap { CompletableFuture.completedStage(missing(ApiError(404, "gone")) as Any?) }

        chain(Params(emptyMap(), null, several)).toCompletableFuture().join()

        seen shouldBe listOf(404)
    }

    @Test
    fun `afterStatus reports a refusal raised by a filter further in`() {
        val seen = mutableListOf<Int>()
        val handler: (Params) -> CompletionStage<Any?> = { CompletableFuture.completedStage("unreached" as Any?) }
        val chain = listOf(
            afterStatus { _, status, _ -> seen += status },
            before { forbidden("not yours") },
        ).wrap(handler)

        runCatching { chain(Params(emptyMap(), null, several)).toCompletableFuture().join() }

        // The point of resolving from what a filter *sees* rather than from
        // something the handler recorded: the handler never ran, and a 403 is
        // still the most interesting request of the day to have counted.
        seen shouldBe listOf(403)
    }

    @Test
    fun `afterStatus says nothing about a request that matched no description`() {
        val seen = mutableListOf<Int>()
        val chain = listOf(afterStatus { _, status, _ -> seen += status })
            .wrap { CompletableFuture.completedStage("handled" as Any?) }

        chain(Params(emptyMap(), null, endpoint = null)).toCompletableFuture().join()

        seen.shouldBeEmpty()
    }
}
