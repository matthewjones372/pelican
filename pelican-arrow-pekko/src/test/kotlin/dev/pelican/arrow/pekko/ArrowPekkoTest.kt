package dev.pelican.arrow.pekko

import dev.pelican.Api
import dev.pelican.ApiError
import dev.pelican.Outcome
import dev.pelican.arrow.ErrorMapper
import dev.pelican.arrow.handledRaise
import dev.pelican.arrow.raise
import dev.pelican.div
import dev.pelican.endpoint
import dev.pelican.errorJson
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.orFail
import dev.pelican.pathParam
import dev.pelican.queryParam
import dev.pelican.test.pekko.inMemory
import org.apache.pekko.stream.javadsl.Source
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * The Arrow binders over a real Pekko route.
 *
 * The unit tests in pelican-arrow already say that a raise becomes the declared
 * failure; what is left to check is that the value they produce is the one this
 * backend renders — the same `Outcome` its own `handledOrFail` produces, at the
 * status the endpoint declared, with the payload decoded back into its type.
 * The requests run through the interpreted route rather than a socket, which is
 * every layer that turns an Outcome into a response.
 */
class ArrowPekkoTest {

    data class Item(val id: Long, val name: String)

    sealed interface Problem {
        data class Unknown(val id: Long) : Problem
        data object Empty : Problem
    }

    private val noSuchItem = errorJson<ApiError>(404, "No item with that id")
    private val nothingToStream = errorJson<ApiError>(409, "Nothing to stream")

    private val toResponse: ErrorMapper<Problem, ApiError> = { problem ->
        when (problem) {
            is Problem.Unknown -> noSuchItem(ApiError(404, "No item ${problem.id}"))
            is Problem.Empty -> nothingToStream(ApiError(409, "Nothing to stream"))
        }
    }

    private val itemId = pathParam<Long>("itemId")
    private val limit = queryParam<Int>("limit")

    private val getItem = endpoint(itemId) {
        get("items" / itemId)
        json<Item>() orFail noSuchItem
    }

    private val streamItems = endpoint(limit) {
        get("items" / "stream")
        ndjson<Item>() orFail nothingToStream
    }

    private val watchItems = endpoint(limit) {
        get("items" / "watch")
        ndjson<Item>() orFail nothingToStream
    }

    private fun app() = Api(
        endpoints = listOf(
            getItem.handledRaise(toResponse) { id ->
                if (id == 1L) Item(1, "widget") else raise(Problem.Unknown(id))
            },
            streamItems.streamedRaise(toResponse) { max ->
                if (max <= 0) raise(Problem.Empty)
                Source.from((1..max).map { Item(it.toLong(), "item-$it") })
            },
            watchItems.streamedByRaise(toResponse) { max ->
                if (max <= 0) raise(Problem.Empty)
                CompletableFuture.completedStage(Source.from((1..max).map { Item(it.toLong(), "item-$it") }))
            },
        ),
        codecs = JacksonCodecs,
    ).inMemory("arrow-pekko")

    @Test
    fun `a value handler answers with its value`() {
        app().use { client ->
            assertEquals(Item(1, "widget"), client.call(getItem, 1L))
        }
    }

    @Test
    fun `a raise answers with the declared failure`() {
        app().use { client ->
            val result = client.outcome(getItem, 9L)
            val err = result as Outcome.Err
            assertSame(noSuchItem, err.declared)
            assertEquals(ApiError(404, "No item 9"), err.error)
            assertEquals(404, client.response(getItem, 9L).status)
        }
    }

    @Test
    fun `a streaming handler that does not raise streams its elements`() {
        app().use { client ->
            assertEquals(
                listOf(Item(1, "item-1"), Item(2, "item-2")),
                client.collect(streamItems, 2),
            )
        }
    }

    @Test
    fun `a stream that raises before its first element answers with the failure`() {
        app().use { client ->
            val err = client.outcome(streamItems, 0) as Outcome.Err
            assertSame(nothingToStream, err.declared)
            assertEquals(409, client.response(streamItems, 0).status)
        }
    }

    @Test
    fun `the stage-returning stream binder behaves the same`() {
        app().use { client ->
            assertEquals(listOf(Item(1, "item-1")), client.collect(watchItems, 1))
            assertSame(nothingToStream, (client.outcome(watchItems, 0) as Outcome.Err).declared)
        }
    }
}
