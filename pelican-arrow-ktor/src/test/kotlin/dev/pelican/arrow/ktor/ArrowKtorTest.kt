package dev.pelican.arrow.ktor

import dev.pelican.Api
import dev.pelican.ApiError
import dev.pelican.arrow.ErrorMapper
import dev.pelican.arrow.ensure
import dev.pelican.arrow.raise
import dev.pelican.div
import dev.pelican.endpoint
import dev.pelican.errorJson
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.ktor.pelican
import dev.pelican.orFail
import dev.pelican.pathParam
import dev.pelican.queryParam
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The Arrow binders over a real Ktor application.
 *
 * The claim these tests exist for is the one that is specific to this backend:
 * a handler here suspends, and `raise` still short-circuits across the
 * suspension — the awaits happen *inside* the `fold`, so a raise after one is
 * the same jump as a raise before it.
 *
 * The responses are read as text rather than through the typed test client,
 * because there is no in-memory transport for Ktor; what is being checked is
 * the status and the rendered body, which text is enough for.
 */
class ArrowKtorTest {

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

    /** Stands in for a repository: the lookup suspends before it decides. */
    private suspend fun find(id: Long): Item? {
        delay(1)
        return if (id == 1L) Item(1, "widget") else null
    }

    private fun api() = Api(
        endpoints = listOf(
            getItem.handledRaise(toResponse) { id ->
                find(id) ?: raise(Problem.Unknown(id))
            },
            streamItems.streamedRaise(toResponse) { max ->
                ensure(max > 0) { Problem.Empty }
                delay(1)
                (1..max).asFlow().map { Item(it.toLong(), "item-$it") }
            },
        ),
        codecs = JacksonCodecs,
    )

    private fun served(block: suspend (HttpClient) -> Unit) = testApplication {
        application { pelican(api()) }
        block(client)
    }

    @Test
    fun `a suspending handler answers with its value`() = served { client ->
        val res = client.get("/items/1")
        assertEquals(200, res.status.value)
        assertEquals("""{"id":1,"name":"widget"}""", res.bodyAsText())
    }

    @Test
    fun `a raise after a suspension is still the declared failure`() = served { client ->
        val res = client.get("/items/9")
        assertEquals(404, res.status.value)
        assertEquals("""{"status":404,"error":"No item 9","detail":null}""", res.bodyAsText())
    }

    @Test
    fun `a streaming handler that does not raise streams its elements`() = served { client ->
        val res = client.get("/items/stream?limit=2")
        assertEquals(200, res.status.value)
        assertEquals(
            listOf("""{"id":1,"name":"item-1"}""", """{"id":2,"name":"item-2"}"""),
            res.bodyAsText().lines().filter { it.isNotBlank() },
        )
    }

    @Test
    fun `a stream that raises before its first element answers with the failure`() = served { client ->
        val res = client.get("/items/stream?limit=0")
        assertEquals(409, res.status.value)
        assertEquals("""{"status":409,"error":"Nothing to stream","detail":null}""", res.bodyAsText())
    }
}
