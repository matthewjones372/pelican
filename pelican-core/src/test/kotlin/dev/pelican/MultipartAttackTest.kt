package dev.pelican

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.time.Duration

/**
 * The parser, attacked rather than exercised.
 *
 * `MultipartTest` next door states what the parser is for. This one states what
 * it does when the envelope is hostile or merely broken — because it is core's
 * own parser rather than a library's, and an envelope arrives from whoever
 * chose to send it.
 */
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
        assertEquals(content, values.upload().text())
    }

    @Test
    fun `a delimiter-shaped line with the wrong line ending is content`() {
        val content = "x\n--$boundary\ny"
        val values = decode(envelope(part("caption", "c"), part("file", content, "f")))
        assertEquals(content, values.upload().text())
    }

    // ---------------------------------------------------------------- budget

    @Test
    fun `many small text parts cannot add up past the limit`() {
        // The budget is shared across parts, so a caller cannot spend it twice.
        val parts = Array(6) { part("caption", "z".repeat(400)) }
        assertThrows<PayloadTooLarge> { decode(envelope(*parts), limit = 1000) }
    }

    @Test
    fun `an undeclared part of absurd size is skipped without being buffered`() {
        val huge = part("ignored", "q".repeat(4_000_000))
        val values = decode(
            envelope(huge, part("caption", "c"), part("file", "F", "f")),
            limit = 1024,
        )
        assertEquals("c", values[caption])
        assertEquals("F", values.upload().text())
    }

    // ------------------------------------------------------- malformed input

    @Test
    fun `an entirely empty body is a 400 rather than a hang`() {
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            assertThrows<ApiException> { decode(ByteArray(0)) }
        }
    }

    @Test
    fun `a content type with no boundary parameter is a 400`() {
        val failure = assertThrows<ApiException> {
            decode(envelope(part("caption", "c")), contentType = "multipart/form-data")
        }
        assertEquals(400, failure.status)
    }

    @Test
    fun `a part with no name attribute is skipped rather than crashing`() {
        val anonymous = "Content-Disposition: form-data\r\n\r\nanon"
        val values = decode(envelope(anonymous, part("caption", "c"), part("file", "F", "f")))
        assertEquals("c", values[caption])
    }

    @Test
    fun `a zero length file part is a file, not an absent one`() {
        val values = decode(envelope(part("caption", "c"), part("file", "", "empty.txt")))
        assertEquals("empty.txt", values.upload().filename)
        assertEquals("", values.upload().text())
    }

    @Test
    fun `an envelope cut off mid-file fails rather than hanging`() {
        val truncated = (
            "--$boundary\r\n" + part("caption", "c") + "\r\n" +
                "--$boundary\r\n" + part("file", "partial", "f")
            ).toByteArray(Charsets.ISO_8859_1)

        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            assertThrows<Exception> { decode(truncated).upload().text() }
        }
    }

    // -------------------------------------------------------------- metadata

    @Test
    fun `a filename containing a quoted semicolon survives intact`() {
        val values = decode(envelope(part("caption", "c"), part("file", "F", "a;b .txt")))
        assertEquals("a;b .txt", values.upload().filename)
    }

    @Test
    fun `a traversal filename is handed over verbatim, not sanitised behind your back`() {
        // Documented as attacker-controlled and advisory. Quietly rewriting it
        // would be worse than leaving it alone: a handler would then be trusting
        // a value nothing actually guarantees.
        val values = decode(envelope(part("caption", "c"), part("file", "F", "../../etc/passwd")))
        assertEquals("../../etc/passwd", values.upload().filename)
    }
}
