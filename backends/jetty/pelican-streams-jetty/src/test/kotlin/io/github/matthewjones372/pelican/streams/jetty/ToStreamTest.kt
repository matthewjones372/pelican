package io.github.matthewjones372.pelican.streams.jetty

import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.mapOrFail
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.runCollect
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.jetty.handledBy
import io.github.matthewjones372.pelican.ndjsonIn
import io.github.matthewjones372.pelican.test.frames
import io.github.matthewjones372.pelican.test.jetty.inMemory
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** An upload arrives as frames, the handler converts once, and every operator after that is lark's, on Forks. */
class ToStreamTest {

    data class Note(val text: String)

    data class Tally(val counted: Int)

    data class Shouted(val all: List<String>)

    data class Empty(val id: Int)

    private val counted = endpoint(ndjsonIn<Note>()) {
        post("counted")
        operationId = "counted"
        json<Tally>()
    }

    private val shouted = endpoint(ndjsonIn<Note>()) {
        post("shouted")
        operationId = "shouted"
        json<Shouted>()
    }

    private val refused = endpoint(ndjsonIn<Note>()) {
        post("refused")
        operationId = "refused"
        json<Tally>()
    }

    private fun app() = api(
        endpoints = listOf(
            counted handledBy { rows ->
                rows.toStream()
                    .runFold(0) { seen, _ -> seen + 1 }
                    .run(Forks())
                    .thenApply { Tally(it.valueOr(-1)) }
            },
            shouted handledBy { rows ->
                rows.toStream()
                    .mapOrFail<Nothing, Note, String> { it.text.uppercase() }
                    .runCollect()
                    .run(Forks())
                    .thenApply { Shouted(it.valueOr(emptyList())) }
            },
            refused handledBy { rows ->
                rows.toStream()
                    .mapOrFail { row -> row.text.ifEmpty { raise(Empty(1)) } }
                    .runFold(0) { seen, _ -> seen + 1 }
                    .run(Forks())
                    .thenApply { Tally(if (it is Exit.Failed) REFUSED else it.valueOr(-1)) }
            },
        ),
        codecs = JacksonCodecs,
    ).inMemory(Handlers.onVirtualThreads)

    @Test
    fun `every frame of the upload reaches the stream`() {
        app().use { it.call(counted, notes("one", "two", "three")) shouldBe Tally(3) }
    }

    @Test
    fun `the frames arrive in the order they were sent`() {
        app().use {
            it.call(shouted, notes("a", "b", "c")) shouldBe Shouted(listOf("A", "B", "C"))
        }
    }

    @Test
    fun `an upload with no frames is an empty stream rather than a failure`() {
        app().use { it.call(counted, notes()) shouldBe Tally(0) }
    }

    @Test
    fun `a row the handler refuses comes back as a named failure, not a thrown one`() {
        app().use { it.call(refused, notes("fine", "", "never read")) shouldBe Tally(REFUSED) }
    }

    private fun notes(vararg text: String) = frames(text.map { Note(it) })

    private companion object {
        /** Distinct from any count, so the assertion cannot pass by arithmetic accident. */
        const val REFUSED = -99
    }
}

/** `Done`'s value, or [fallback] for the two ways a run does not produce one. */
internal fun <A : Any> Exit<*, A>.valueOr(fallback: A): A = if (this is Exit.Done) value else fallback
