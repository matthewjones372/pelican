package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
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

        chain(params()).toCompletableFuture().join() shouldBe "handled"
        log shouldBe listOf("a in", "b in", "b out", "a out")
    }

    @Test
    fun `a filter that throws short-circuits the handler`() {
        var ran = false
        val chain = listOf(before { forbidden("no") })
            .wrap { ran = true; CompletableFuture.completedStage("handled" as Any?) }

        val failure = shouldThrow<ApiException> { chain(params()) }
        failure.status shouldBe 403
        ran shouldBe false
    }

    @Test
    fun `an empty chain is the handler itself`() {
        emptyList<Filter>().wrap(handler)(params()).toCompletableFuture().join() shouldBe "handled"
    }

    @Test
    fun `onlyWhen skips the filter for endpoints it does not match`() {
        val seen = mutableListOf<String>()
        val chain = listOf(
            before { seen += "checked" }.onlyWhen { it.method == Method.POST },
        ).wrap(handler)

        chain(params()).toCompletableFuture().join()
        seen shouldBe emptyList<String>()
    }

    @Test
    fun `attributes carry a filter's finding to the handler`() {
        val who = attribute<String>("who")
        val chain = listOf(before { it[who] = "ada" })
            .wrap { p -> CompletableFuture.completedStage(p[who] as Any?) }

        chain(params()).toCompletableFuture().join() shouldBe "ada"
    }

    @Test
    fun `reading an attribute nothing set names the wiring mistake`() {
        val who = attribute<String>("who")
        val failure = shouldThrow<IllegalStateException> { params()[who] }
        failure.message shouldContain "nothing set it"
        // And the non-throwing read, for a filter that is genuinely optional.
        params().find(who) shouldBe null
    }

    @Test
    fun `after sees the handler's result`() {
        val seen = mutableListOf<Any?>()
        val chain = listOf(after { _, result, error -> seen += (error ?: result) }).wrap(handler)

        chain(params()).toCompletableFuture().join()
        seen shouldBe listOf<Any?>("handled")
    }

    @Test
    fun `after sees a failure too, and does not swallow it`() {
        val seen = mutableListOf<Any?>()
        val boom = RuntimeException("boom")
        val chain = listOf(after { _, _, error -> seen += error })
            .wrap { CompletableFuture.failedStage<Any?>(boom) }

        val failure = shouldThrow<CompletionException> {
            chain(params()).toCompletableFuture().join()
        }
        unwrapCompletion(failure) shouldBe boom
        seen shouldBe listOf<Any?>(boom)
    }
}
