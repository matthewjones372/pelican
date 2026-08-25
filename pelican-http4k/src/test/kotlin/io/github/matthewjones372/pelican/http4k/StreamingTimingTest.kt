package io.github.matthewjones372.pelican.http4k

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The same invariant `pelican-pekko`'s tests hold for Pekko, over a socket:
 * a streamed row reaches the caller when it is produced, not when the last one
 * is.
 *
 * [StreamingTest] shows the handler's sequence is pulled lazily, which is this
 * module's half of the bargain. The other half belongs to the server, and is
 * why [StreamingSunHttp] exists — with http4k's stock `SunHttp` underneath,
 * every assertion below fails by about a second.
 *
 * The margins are wide on purpose: the claim is "the first row does not wait
 * for the last", not a latency budget.
 */
class StreamingTimingTest {

    private val rows = 10
    private val gapMillis = 100L

    private fun api() = api(
        endpoints = listOf(
            streamItems streamedNow { max ->
                (1..max).asSequence().map { i ->
                    Thread.sleep(gapMillis)
                    Item(i.toLong(), "item-$i")
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

            // The stream cannot have finished before the last sleep, so a first
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
