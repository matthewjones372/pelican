package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What core writes for one event, since the three interpreters all write it.
 */
class SseFramesTest {

    data class Tick(val seq: Int)

    private val codec = object : BodyCodec<Tick> {
        override fun encodeToString(value: Tick): String = """{"seq":${value.seq}}"""
        override fun decodeFromString(text: String): Tick = error("not read here")
    }

    private fun sse(
        eventName: String? = null,
        id: ((Tick) -> String)? = null,
        retry: Duration? = null,
    ): SseOutput<Tick> = SseOutput(200, typeOf<Tick>(), eventName, null, null, id, retry)

    @Test
    fun `a stream that names no ids writes the frame it always wrote`() {
        sse(eventName = "tick").frame(codec, Tick(1)) shouldBe "event: tick\ndata: {\"seq\":1}\n\n"
        sse().frame(codec, Tick(1)) shouldBe "data: {\"seq\":1}\n\n"
    }

    @Test
    fun `an id extractor puts the event's own id on every frame`() {
        sse(eventName = "tick", id = { it.seq.toString() }).frame(codec, Tick(7)) shouldBe
            "event: tick\nid: 7\ndata: {\"seq\":7}\n\n"

        sse(id = { it.seq.toString() }).frame(codec, Tick(7)) shouldBe "id: 7\ndata: {\"seq\":7}\n\n"
    }

    /**
     * `retry` sets the stream's reconnection time rather than describing an
     * event, and a frame carrying no `data` dispatches nothing — so it goes out
     * once, before the first event, and no event is invented to carry it.
     */
    @Test
    fun `retry is written once ahead of the stream and never on a frame`() {
        val out = sse(eventName = "tick", retry = 15.seconds)

        out.prelude() shouldBe "retry: 15000\n\n"
        out.frame(codec, Tick(1)) shouldNotContain "retry"
    }

    @Test
    fun `a stream with no retry hint sends nothing ahead of it`() {
        sse(eventName = "tick").prelude().shouldBeNull()
    }

    @Test
    fun `a retry too small to be a whole millisecond is refused where it is declared`() {
        shouldThrow<IllegalArgumentException> { sse(retry = 400.microseconds) }
            .message.orEmpty() shouldContain "whole milliseconds"
    }

    @Test
    fun `a negative retry is refused too`() {
        shouldThrow<IllegalArgumentException> { sse(retry = (-1).seconds) }
    }
}
