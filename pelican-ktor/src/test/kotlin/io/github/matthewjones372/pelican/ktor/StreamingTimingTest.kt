package io.github.matthewjones372.pelican.ktor

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The same invariant the other two backends' tests hold, over a socket: a
 * streamed row reaches the caller when it is produced, not when the last one
 * is.
 *
 * There are two halves to it. The handler's flow must be collected as the body
 * is written rather than before — that is what makes `delay` inside the flow
 * show up as a gap between rows — and each frame must be flushed rather than
 * left in the engine's buffer. Both halves live in `Responses.kt`, which is why
 * this backend needs no engine of its own to make streaming work.
 *
 * The margins are wide on purpose: the claim is "the first row does not wait
 * for the last", not a latency budget.
 */
class StreamingTimingTest {

    private val rows = 10
    private val gapMillis = 100L

    private fun api() = Api(
        endpoints = listOf(
            streamItems streamedNow { max ->
                flow {
                    for (i in 1..max) {
                        delay(gapMillis)
                        emit(Item(i.toLong(), "item-$i"))
                    }
                }
            },
        ),
        codecs = JacksonCodecs,
    )

    @Test
    fun `the first row arrives long before the last`() {
        val server = api().start(port = 0)
        try {
            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("${server.baseUrl}/items/stream?limit=$rows")).build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )

            val started = System.nanoTime()
            val reader = response.body().bufferedReader()

            val first = reader.readLine()
            val firstRowMillis = millisSince(started)

            var count = 1
            while (reader.readLine() != null) count++
            val allRowsMillis = millisSince(started)

            first shouldBe """{"id":1,"name":"item-1"}"""
            count shouldBe rows

            // The stream cannot have finished before the last delay, so a first
            // row that arrives near the end arrived with it.
            withClue("the test's own premise failed: all $rows rows in ${allRowsMillis}ms") {
                allRowsMillis.toDouble() shouldBeGreaterThan rows * gapMillis * 0.8
            }
            withClue("the first row took ${firstRowMillis}ms of ${allRowsMillis}ms, so it waited for the rest") {
                firstRowMillis shouldBeLessThan allRowsMillis / 2
            }
        } finally {
            server.stop()
        }
    }

    private fun millisSince(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000
}
