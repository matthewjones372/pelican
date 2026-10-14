package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.decode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.time.Duration

class MultipartAttackTest {

    private val caption = textPart<String>("caption")
    private val file = filePart("file", contentType = "text/plain")
    private val body = MultipartBody(listOf(caption, file))

    private val boundary = "b0undary"

    /** One part's headers and content, without the delimiters around it. */
    private fun part(name: String, content: String, filename: String? = null): String =
        buildString {
            append("Content-Disposition: form-data; name=\"").append(name).append('"')
            if (filename != null) append("; filename=\"").append(filename).append('"')
            append("\r\n\r\n").append(content)
        }

    private fun envelope(vararg parts: String): ByteArray =
        (parts.joinToString("") { "--$boundary\r\n$it\r\n" } + "--$boundary--\r\n")
            .toByteArray(Charsets.ISO_8859_1)

    private fun decode(
        raw: ByteArray,
        contentType: String = "multipart/form-data; boundary=$boundary",
        limit: Long = 8192,
    ): Map<ParamKey<*>, Any?> =
        LinkedHashMap<ParamKey<*>, Any?>()
            .also { body.decode(contentType, ByteArrayInputStream(raw), limit, it) }

    private fun Map<ParamKey<*>, Any?>.upload() = this[file] as UploadedFile

    // ---------------------------------------------------- boundary confusion

    @Test
    fun `the boundary appearing inside the file is content, not a delimiter`() {
        // Neither occurrence qualifies: the first has no CRLF before it, the
        // second is followed by neither CRLF nor `--`.
        val content = "alpha--${boundary}XX beta\r\nplain--${boundary}9 gamma"
        val values = decode(envelope(part("caption", "c"), part("file", content, "f")))
        values.upload().text() shouldBe content
    }

    @Test
    fun `a delimiter-shaped line with the wrong line ending is content`() {
        val content = "x\n--$boundary\ny"
        val values = decode(envelope(part("caption", "c"), part("file", content, "f")))
        values.upload().text() shouldBe content
    }

    // ---------------------------------------------------------------- budget

    @Test
    fun `many small text parts cannot add up past the limit`() {
        // The budget is shared across parts, so a caller cannot spend it twice.
        val parts = Array(6) { part("caption", "z".repeat(400)) }
        shouldThrow<PayloadTooLarge> { decode(envelope(*parts), limit = 1000) }
    }

    @Test
    fun `an undeclared part of absurd size is skipped without being buffered`() {
        val huge = part("ignored", "q".repeat(4_000_000))
        val values = decode(
            envelope(huge, part("caption", "c"), part("file", "F", "f")),
            limit = 1024,
        )
        values[caption] shouldBe "c"
        values.upload().text() shouldBe "F"
    }

    // ------------------------------------------------------- malformed input

    @Test
    fun `an entirely empty body is a 400 rather than a hang`() {
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            shouldThrow<ApiException> { decode(ByteArray(0)) }
        }
    }

    @Test
    fun `a content type with no boundary parameter is a 400`() {
        val failure = shouldThrow<ApiException> {
            decode(envelope(part("caption", "c")), contentType = "multipart/form-data")
        }
        failure.status shouldBe 400
    }

    @Test
    fun `a part with no name attribute is skipped rather than crashing`() {
        val anonymous = "Content-Disposition: form-data\r\n\r\nanon"
        val values = decode(envelope(anonymous, part("caption", "c"), part("file", "F", "f")))
        values[caption] shouldBe "c"
    }

    @Test
    fun `a zero length file part is a file, not an absent one`() {
        val values = decode(envelope(part("caption", "c"), part("file", "", "empty.txt")))
        values.upload().filename shouldBe "empty.txt"
        values.upload().text() shouldBe ""
    }

    @Test
    fun `an envelope cut off mid-file fails rather than hanging`() {
        val truncated = (
            "--$boundary\r\n" + part("caption", "c") + "\r\n" +
                "--$boundary\r\n" + part("file", "partial", "f")
            ).toByteArray(Charsets.ISO_8859_1)

        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            shouldThrow<Exception> { decode(truncated).upload().text() }
        }
    }

    // -------------------------------------------------------------- metadata

    @Test
    fun `a filename containing a quoted semicolon survives intact`() {
        val values = decode(envelope(part("caption", "c"), part("file", "F", "a;b .txt")))
        values.upload().filename shouldBe "a;b .txt"
    }

    @Test
    fun `a traversal filename is handed over verbatim, not sanitised behind your back`() {
        // Documented as attacker-controlled and advisory. Quietly rewriting it
        // would be worse than leaving it alone: a handler would then be trusting
        // a value nothing actually guarantees.
        val values = decode(envelope(part("caption", "c"), part("file", "F", "../../etc/passwd")))
        values.upload().filename shouldBe "../../etc/passwd"
    }
}
