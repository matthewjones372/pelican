package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.filePart
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.net.Socket

/**
 * Pekko's `ChunkedBodyLimitTest` check, on Jetty: an over-limit chunked body is refused on a connection that is
 * still usable. A raw socket, because a client that opens a second connection would pass either way.
 */
class RefusedBodyKeepsTheConnectionTest {

    data class Jotted(val text: String)

    companion object {
        private val notes = endpoint(jsonBody<Jotted>()) {
            post("notes")
            text()
        }

        private val upload = endpoint(filePart("file", contentType = "text/plain")) {
            post("upload")
            text()
        }

        private val server = api(
            listOf(notes handledNow { "read ${it.text.length}" }, upload handledNow { "read ${it.bytes().size}" }),
            JacksonCodecs,
        ) {
            maxBodyBytes = 1_024
        }.start(port = 0)

        @JvmStatic
        @AfterAll
        fun stop() = server.stop()

        private const val LAST_CHUNK = "0\r\n\r\n"

        private const val FILE_PART =
            "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f\"\r\n\r\nhello\r\n--B--\r\n"

        /** Long enough that a hung read fails the test rather than the whole run. */
        private const val SOCKET_TIMEOUT_MILLIS = 5_000
    }

    @Test
    fun `the refusal arrives on a connection that is still usable`() {
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val out = socket.getOutputStream()
            val reader = socket.getInputStream().bufferedReader()

            out.write(chunked("""{"text":"${"x".repeat(4_000)}"}""").toByteArray())
            out.flush()
            val refusal = readHead(reader)

            withClue(refusal) {
                refusal.first() shouldStartWith "HTTP/1.1 413 "
                refusal.none { it.equals("Connection: close", ignoreCase = true) } shouldBe true
            }
            drainBody(reader, refusal)

            out.write(chunked("""{"text":"hello"}""").toByteArray())
            out.flush()

            withClue("the same socket, no reconnection") { readHead(reader).first() shouldBe "HTTP/1.1 200 OK" }
        }
    }

    /**
     * Spec 0052: Jetty, answered before the body is read, closed the connection and reset the client still writing
     * it. The body here is sent only once the refusal has been read, so nothing but the server reading it can pass.
     */
    @Test
    fun `a body still arriving when it is refused is read, on a connection that is still usable`() {
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val out = socket.getOutputStream()
            val reader = socket.getInputStream().bufferedReader()

            // A multipart type naming no boundary is refused before a byte of the body is read.
            out.write(head("/upload", "multipart/form-data").toByteArray())
            out.flush()
            val refusal = readHead(reader)

            withClue(refusal) {
                refusal.first() shouldStartWith "HTTP/1.1 400 "
                refusal.none { it.equals("Connection: close", ignoreCase = true) } shouldBe true
            }
            drainBody(reader, refusal)

            out.write((chunk(FILE_PART) + LAST_CHUNK).toByteArray())
            out.write(chunked("""{"text":"hello"}""").toByteArray())
            out.flush()

            withClue("the same socket, no reconnection") { readHead(reader).first() shouldBe "HTTP/1.1 200 OK" }
        }
    }

    /** A handler reading a streamed file to its closing boundary has not read the chunked framing after it. */
    @Test
    fun `an upload read to its closing boundary is read to its end, on a connection that is still usable`() {
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val out = socket.getOutputStream()
            val reader = socket.getInputStream().bufferedReader()

            out.write(head("/upload", "multipart/form-data; boundary=B").toByteArray())
            out.write(
                chunk(FILE_PART).toByteArray(),
            )
            out.flush()
            val answer = readHead(reader)

            withClue(answer) {
                answer.first() shouldBe "HTTP/1.1 200 OK"
                answer.none { it.equals("Connection: close", ignoreCase = true) } shouldBe true
            }
            drainBody(reader, answer)

            out.write(LAST_CHUNK.toByteArray())
            out.write(chunked("""{"text":"hello"}""").toByteArray())
            out.flush()

            withClue("the same socket, no reconnection") { readHead(reader).first() shouldBe "HTTP/1.1 200 OK" }
        }
    }

    private fun head(path: String, contentType: String): String =
        "POST $path HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Content-Type: $contentType\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n"

    private fun chunk(text: String): String = Integer.toHexString(text.toByteArray().size) + "\r\n" + text + "\r\n"

    private fun chunked(body: String): String =
        "POST /notes HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Content-Type: application/json\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n" +
            Integer.toHexString(body.toByteArray().size) + "\r\n" + body + "\r\n0\r\n\r\n"

    private fun readHead(reader: BufferedReader): List<String> =
        generateSequence { reader.readLine() }
            .takeWhile { it.isNotEmpty() }
            .toList()
            .ifEmpty { error("The server closed the connection instead of answering.") }

    private fun drainBody(reader: BufferedReader, head: List<String>) {
        val length = head
            .first { it.startsWith("Content-Length:", ignoreCase = true) }
            .substringAfter(':')
            .trim()
            .toInt()
        val body = CharArray(length)
        var read = 0
        while (read < length) {
            val n = reader.read(body, read, length - read)
            check(n >= 0) { "The connection ended part-way through the body." }
            read += n
        }
    }
}
