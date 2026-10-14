package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.NdjsonFrames
import io.github.matthewjones372.pelican.spi.RequestBodyCodecs
import io.github.matthewjones372.pelican.spi.renderError
import io.github.matthewjones372.pelican.spi.requestBodyCodec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import kotlin.reflect.KType

/**
 * Where a frame ends, what one decodes to, and what a body breaking either rule
 * is told. Decided here, once, because three interpreters feeding the same
 * bytes in have to reach the same answers.
 */
class NdjsonFramesTest {

    /** `{"n":1}` and nothing else, so a malformed frame is malformed for a stated reason. */
    private object Numbers : Codecs {
        override fun schema(type: KType, components: SchemaComponents): JsonObj = jsonObj { "type" to "integer" }

        @Suppress("UNCHECKED_CAST")
        override fun <T> codec(type: KType): BodyCodec<T> = object : BodyCodec<Int> {
            override fun encodeToString(value: Int) = """{"n":$value}"""

            override fun decodeFromString(text: String): Int =
                text.trim().removeSurrounding("""{"n":""", "}").toIntOrNull()
                    ?: throw BodyDecodeFailure("'${text.trim()}' is not a number in an envelope")
        } as BodyCodec<T>
    }

    private val body = ndjsonIn<Int>()

    private fun codecs(): RequestBodyCodecs = checkNotNull(Numbers.requestBodyCodec(body))

    private fun reader(maxFrameBytes: Long = 1024): NdjsonFrames =
        NdjsonFrames(codecs(), "application/x-ndjson", maxFrameBytes)

    /** The whole body in one chunk, which is what a small upload is. */
    private fun read(text: String, maxFrameBytes: Long = 1024): List<Any?> {
        val frames = reader(maxFrameBytes)
        return frames.push(text.toByteArray(StandardCharsets.UTF_8)) + frames.end()
    }

    @Test
    fun `a newline ends a frame and each frame decodes on its own`() {
        read("{\"n\":1}\n{\"n\":2}\n") shouldBe listOf(1, 2)
    }

    @Test
    fun `the last document need not be followed by a newline`() {
        read("{\"n\":1}\n{\"n\":2}") shouldBe listOf(1, 2)
    }

    @Test
    fun `a blank line is not a document`() {
        read("\n{\"n\":7}\n\n") shouldBe listOf(7)
    }

    @Test
    fun `an empty body carries no frames at all`() {
        read("") shouldBe emptyList()
    }

    /**
     * The chunks a backend hands over are the network's, so a frame split
     * across two of them is the ordinary case rather than an edge one.
     */
    @Test
    fun `a frame split across chunks is one frame`() {
        val frames = reader()
        val first = frames.push("{\"n\":1".toByteArray(StandardCharsets.UTF_8))
        val rest = frames.push("}\n{\"n\":2}\n".toByteArray(StandardCharsets.UTF_8))

        withClue("nothing completes until the newline arrives") { first shouldBe emptyList() }
        rest shouldBe listOf(1, 2)
    }

    @Test
    fun `a malformed frame is a 400 naming which frame it was`() {
        val failure = shouldThrow<BodyDecodeFailure> { read("{\"n\":1}\nnot json\n{\"n\":3}\n") }

        failure.message shouldContain "Frame 2"
        renderError(failure, null).error.status shouldBe 400
    }

    /**
     * Blank lines are counted, so the number a caller is handed is the line of
     * the upload they can go and look at.
     */
    @Test
    fun `the frame a refusal names is the line of the body`() {
        shouldThrow<BodyDecodeFailure> { read("\n\nnot json\n") }.message shouldContain "Frame 3"
    }

    @Test
    fun `a frame over the cap is a 413 rather than a frame held whole`() {
        val long = "{\"n\":" + "9".repeat(64) + "}"
        val failure = shouldThrow<PayloadTooLarge> { read("{\"n\":1}\n$long", maxFrameBytes = 16) }

        failure.limit shouldBe 16L
        failure.message shouldContain "Frame 2"
        renderError(failure, null).error.status shouldBe 413
    }

    /** The cap counts bytes, for the reason `maxBodyBytes` counts them. */
    @Test
    fun `the cap counts bytes rather than characters`() {
        shouldThrow<PayloadTooLarge> { read("{\"n\":五五五五}", maxFrameBytes = 14) }
    }

    /**
     * The two refusals a streamed upload can raise are the same typed families
     * a strict body raises, so the refusal counter sees an upload's frames
     * without knowing anything about framing.
     */
    @Test
    fun `both frame refusals reach the observer under the reasons the counter maps`() {
        val seen = mutableListOf<String>()
        val api = api(endpoints = emptyList()) {
            onRefusal { reason, status, _ -> seen += "${reason.label} $status" }
        }

        renderError(BodyDecodeFailure("Frame 2 of the request body could not be read: nope"), api)
        renderError(PayloadTooLarge(16, "Frame 2 of the request body is longer than 16 bytes"), api)

        seen shouldBe listOf("decode 400", "body_limit 413")
    }

    /** As every refusal does: the envelope is the service's, not this file's. */
    @Test
    fun `a frame refusal is written by the renderer the service configured`() {
        val rendered = renderError(
            BodyDecodeFailure("Frame 2 of the request body could not be read: nope"),
            api(endpoints = emptyList()) { refusals(ProblemDetails) },
        )

        rendered.body.mediaType shouldBe "application/problem+json"
        String(rendered.body.bytes, StandardCharsets.UTF_8) shouldContain
            """"detail":"Frame 2 of the request body could not be read: nope""""
    }
}
