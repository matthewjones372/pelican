package dev.pelican

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The multipart parser, which is core's rather than any backend's — so this is
 * where its behaviour is written down, once, for all three.
 *
 * The interesting cases are the ones a naive reader gets wrong: a boundary that
 * straddles two reads from the source, content that begins with the boundary's
 * first few bytes without being one, and a part nobody asked for.
 */
class MultipartTest {

    private val caption = textPart<String>("caption")
    private val count = textPart<Int>("count").default(1)
    private val notes = textPart<String>("notes").optional()
    private val file = filePart("file", contentType = "text/plain")

    private val body = MultipartBody(listOf(caption, count, notes, file))

    /** An envelope written the way a browser writes one. */
    private fun envelope(vararg parts: String, boundary: String = "b0undary"): InputStream =
        ByteArrayInputStream(
            (parts.joinToString("") { "--$boundary\r\n$it\r\n" } + "--$boundary--\r\n")
                .toByteArray(Charsets.UTF_8),
        )

    private fun text(name: String, value: String) =
        "Content-Disposition: form-data; name=\"$name\"\r\n\r\n$value"

    private fun upload(name: String, filename: String, content: String) =
        "Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n" +
            "Content-Type: text/plain\r\n\r\n$content"

    private fun decode(
        input: InputStream,
        contentType: String = "multipart/form-data; boundary=b0undary",
        limit: Long = 8192,
    ): MutableMap<ParamKey<*>, Any?> {
        val values = LinkedHashMap<ParamKey<*>, Any?>()
        body.decode(contentType, input, limit, values)
        return values
    }

    // ---------------------------------------------------------------- parts

    @Test
    fun `text parts decode into the types they were declared as`() {
        val values = decode(
            envelope(text("caption", "Holiday"), text("count", "3"), upload("file", "a.txt", "hello")),
        )

        assertEquals("Holiday", values[caption])
        assertEquals(3, values[count])
    }

    @Test
    fun `a part that was not sent falls back to its default, or to null`() {
        val values = decode(envelope(text("caption", "Holiday"), upload("file", "a.txt", "hello")))

        assertEquals(1, values[count])
        assertNull(values[notes])
    }

    @Test
    fun `a required part that was not sent is a 400 naming it`() {
        val failure = assertThrows<ApiException> { decode(envelope(text("count", "3"))) }

        assertEquals(400, failure.status)
        assertTrue("caption" in failure.message, failure.message)
    }

    @Test
    fun `a part nobody declared is skipped rather than refused`() {
        val values = decode(
            envelope(
                text("_csrf", "deadbeef"),
                text("caption", "Holiday"),
                text("submit", "Upload"),
                upload("file", "a.txt", "hello"),
            ),
        )

        assertEquals("Holiday", values[caption])
    }

    @Test
    fun `the file part carries what the caller said about it`() {
        val values = decode(envelope(text("caption", "c"), upload("file", "notes.txt", "hello")))
        val uploaded = values[file] as UploadedFile

        assertEquals("notes.txt", uploaded.filename)
        assertEquals("text/plain", uploaded.contentType)
        assertEquals("hello", uploaded.text())
    }

    @Test
    fun `a text part sent after the file part is a 400 that says why`() {
        val failure = assertThrows<ApiException> {
            decode(envelope(upload("file", "a.txt", "hello"), text("caption", "Too late")))
        }

        assertEquals(400, failure.status)
        assertTrue("Send 'caption' before it" in (failure.detail ?: ""), failure.detail ?: "")
    }

    @Test
    fun `a body that is not multipart at all is a 400 rather than a parse failure`() {
        val failure = assertThrows<ApiException> {
            decode(ByteArrayInputStream("{}".toByteArray()), contentType = "application/json")
        }

        assertEquals(400, failure.status)
        assertTrue("multipart" in failure.message, failure.message)
    }

    // ------------------------------------------------------------- the file

    @Test
    fun `the file is not read while the parts are being decoded`() {
        // The whole promise, stated as a test: decoding stops at the file
        // part's first byte, so a large upload has barely been touched by the
        // time the handler is handed it.
        val text = "--b0undary\r\n" + text("caption", "Holiday") + "\r\n" +
            "--b0undary\r\n" + upload("file", "a.txt", "x".repeat(500_000)) + "\r\n--b0undary--\r\n"
        val counting = CountingStream(text.toByteArray(Charsets.UTF_8))

        val values = decode(counting, limit = 1_000_000)

        assertEquals("Holiday", values[caption])
        // The reader buffers, so this is an upper bound rather than an exact
        // byte count — but it is an upper bound a buffering parser could not
        // meet.
        assertTrue(counting.read < 50_000, "read ${counting.read} of ${text.length}")
    }

    @Test
    fun `a file part is read to its boundary and no further`() {
        val values = decode(
            envelope(text("caption", "c"), upload("file", "a.txt", "one\r\ntwo\r\nthree")),
        )

        assertEquals("one\r\ntwo\r\nthree", (values[file] as UploadedFile).text())
    }

    @Test
    fun `content that merely starts like the boundary is content`() {
        // `\r\n--b0` is the first five bytes of the delimiter, so a reader that
        // held nothing back would either cut the part short here or ship the
        // start of a boundary as if it were content.
        val content = "before\r\n--b0 not a boundary\r\n--b0undaryish\r\nafter"
        val values = decode(envelope(text("caption", "c"), upload("file", "a.txt", content)))

        assertEquals(content, (values[file] as UploadedFile).text())
    }

    @Test
    fun `a part larger than one buffer is reassembled whole`() {
        val content = (1..5_000).joinToString("\n") { "line $it" }
        val values = decode(
            envelope(text("caption", "c"), upload("file", "big.txt", content)),
            limit = 1_000_000,
        )

        assertEquals(content, (values[file] as UploadedFile).text())
    }

    @Test
    fun `text parts are bounded by the limit, and the file is not`() {
        val huge = "x".repeat(5_000)

        assertThrows<PayloadTooLarge> {
            decode(envelope(text("caption", huge), upload("file", "a.txt", "hello")), limit = 100)
        }

        // The same limit, with the size in the part that is streamed instead.
        val values = decode(envelope(text("caption", "c"), upload("file", "a.txt", huge)), limit = 100)
        assertEquals(huge, (values[file] as UploadedFile).text())
    }

    // ------------------------------------------------------------ the envelope

    @Test
    fun `a preamble before the first boundary is ignored`() {
        val text = "This is a multipart message, as MIME puts it.\r\n" +
            "--b0undary\r\n" + text("caption", "Holiday") + "\r\n" +
            "--b0undary\r\n" + upload("file", "a.txt", "hello") + "\r\n--b0undary--\r\n"

        val values = decode(ByteArrayInputStream(text.toByteArray()))

        assertEquals("Holiday", values[caption])
    }

    @Test
    fun `a quoted boundary in the content type is the boundary`() {
        val values = decode(
            envelope(text("caption", "Holiday"), upload("file", "a.txt", "hello")),
            contentType = """multipart/form-data; charset=utf-8; boundary="b0undary"""",
        )

        assertEquals("Holiday", values[caption])
    }

    @Test
    fun `an envelope that stops without its closing boundary is a 400`() {
        val truncated = "--b0undary\r\n" + text("caption", "Holiday") + "\r\n--b0undary\r\n" +
            upload("file", "a.txt", "hello")

        val failure = assertThrows<ApiException> {
            val values = decode(ByteArrayInputStream(truncated.toByteArray()))
            (values[file] as UploadedFile).text()
        }

        assertEquals(400, failure.status)
    }

    // ---------------------------------------------------- what is describable

    @Test
    fun `two file parts is a description no handler could be given, so it fails when built`() {
        val failure = assertThrows<IllegalStateException> {
            endpoint(filePart("first"), filePart("second")) {
                post("upload")
                text()
            }
        }

        assertTrue("only the first could be streamed" in (failure.message ?: ""), failure.message ?: "")
    }

    @Test
    fun `parts and a body of another kind both claiming to be the body fails when built`() {
        val failure = assertThrows<IllegalArgumentException> {
            endpoint(caption, jsonBody<String>()) {
                post("upload")
                text()
            }
        }

        assertTrue("the parts are the body" in (failure.message ?: ""), failure.message ?: "")
    }

    @Test
    fun `one part name declared twice fails when built`() {
        val failure = assertThrows<IllegalStateException> {
            endpoint(textPart<String>("caption"), textPart<Int>("caption")) {
                post("upload")
                text()
            }
        }

        assertTrue("more than once" in (failure.message ?: ""), failure.message ?: "")
    }

    /** Counts what has actually been pulled from the source. */
    private class CountingStream(bytes: ByteArray) : InputStream() {
        private val underlying = ByteArrayInputStream(bytes)
        var read = 0
            private set

        override fun read(): Int = underlying.read().also { if (it >= 0) read++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            underlying.read(b, off, len).also { if (it > 0) read += it }
    }
}
