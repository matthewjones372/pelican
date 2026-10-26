package io.github.matthewjones372.pelican.jackson

import io.github.matthewjones372.pelican.BodySink
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.reflect.typeOf

/** Spec 0090: `encodeTo` writes what `encodeToString` would, as UTF-8, streamed from Jackson's own buffer. */
class EncodeToTest {

    data class Note(val id: Long, val text: String)

    private val codec = JacksonCodecs.codec<Note>(typeOf<Note>())

    /** Every write the codec made, and the bytes they add up to. */
    private class Collected : BodySink {
        val out = ByteArrayOutputStream()
        var writes = 0

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            writes++
            out.write(bytes, offset, length)
        }
    }

    private fun collect(note: Note): Collected = Collected().also { codec.encodeTo(note, it) }

    @Test
    fun `the bytes are the string's, in UTF-8`() {
        val note = Note(7, "café ☃ 🐦")
        collect(note).out.toByteArray().decodeToString() shouldBe codec.encodeToString(note)
    }

    /** The String's UTF-8 has no spelling for one, and wrote `?`. */
    @Test
    fun `a lone surrogate is escaped, so it reads back as it was`() {
        val note = Note(8, "half \ud83d of a bird")
        val bytes = collect(note).out.toByteArray()
        bytes.decodeToString() shouldBe """{"id":8,"text":"half \uD83D of a bird"}"""
        codec.decodeFrom(bytes) shouldBe note
    }

    @Test
    fun `a body larger than jackson's buffer arrives in more than one write`() {
        val note = Note(1, "x".repeat(LARGE))
        val collected = collect(note)
        collected.out.toByteArray().decodeToString() shouldBe codec.encodeToString(note)
        withClue("writes for a ${LARGE / 1024} KiB body") { collected.writes shouldBeGreaterThan 1 }
    }

    private companion object {
        const val LARGE = 64 * 1024
    }
}
