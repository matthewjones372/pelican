package dev.pelican

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage

/**
 * The composition rules, on their own — the interpreters are held to the same
 * answers by `FiltersAndHeadersTest` in the example, across all three backends.
 */
class FilterTest {

    private val endpoint = endpoint {
        get("things")
        operationId = "things"
        text()
    }

    private fun params() = Params(emptyMap(), null, endpoint)

    private val handler: (Params) -> CompletionStage<Any?> =
        { CompletableFuture.completedStage("handled" as Any?) }

    private fun record(log: MutableList<String>, name: String) = Filter { p, next ->
        log += "$name in"
        next(p).thenApply { it.also { log += "$name out" } }
    }

    @Test
    fun `the first filter listed is the outermost`() {
        val log = mutableListOf<String>()
        val chain = listOf(record(log, "a"), record(log, "b")).wrap(handler)

        assertEquals("handled", chain(params()).toCompletableFuture().join())
        assertEquals(listOf("a in", "b in", "b out", "a out"), log)
    }

    @Test
    fun `a filter that throws short-circuits the handler`() {
        var ran = false
        val chain = listOf(before { forbidden("no") })
            .wrap { ran = true; CompletableFuture.completedStage("handled" as Any?) }

        val failure = assertThrows<ApiException> { chain(params()) }
        assertEquals(403, failure.status)
        assertEquals(false, ran)
    }

    @Test
    fun `an empty chain is the handler itself`() {
        assertEquals("handled", emptyList<Filter>().wrap(handler)(params()).toCompletableFuture().join())
    }

    @Test
    fun `onlyWhen skips the filter for endpoints it does not match`() {
        val seen = mutableListOf<String>()
        val chain = listOf(
            before { seen += "checked" }.onlyWhen { it.method == Method.POST },
        ).wrap(handler)

        chain(params()).toCompletableFuture().join()
        assertEquals(emptyList<String>(), seen)
    }

    @Test
    fun `attributes carry a filter's finding to the handler`() {
        val who = attribute<String>("who")
        val chain = listOf(before { it[who] = "ada" })
            .wrap { p -> CompletableFuture.completedStage(p[who] as Any?) }

        assertEquals("ada", chain(params()).toCompletableFuture().join())
    }

    @Test
    fun `reading an attribute nothing set names the wiring mistake`() {
        val who = attribute<String>("who")
        val failure = assertThrows<IllegalStateException> { params()[who] }
        assertTrue(failure.message!!.contains("nothing set it"), failure.message!!)
        // And the non-throwing read, for a filter that is genuinely optional.
        assertEquals(null, params().find(who))
    }

    @Test
    fun `after sees the handler's result`() {
        val seen = mutableListOf<Any?>()
        val chain = listOf(after { _, result, error -> seen += (error ?: result) }).wrap(handler)

        chain(params()).toCompletableFuture().join()
        assertEquals(listOf<Any?>("handled"), seen)
    }

    @Test
    fun `after sees a failure too, and does not swallow it`() {
        val seen = mutableListOf<Any?>()
        val boom = RuntimeException("boom")
        val chain = listOf(after { _, _, error -> seen += error })
            .wrap { CompletableFuture.failedStage<Any?>(boom) }

        val failure = assertThrows<CompletionException> {
            chain(params()).toCompletableFuture().join()
        }
        assertEquals(boom, unwrapCompletion(failure))
        assertEquals(listOf<Any?>(boom), seen)
    }
}
