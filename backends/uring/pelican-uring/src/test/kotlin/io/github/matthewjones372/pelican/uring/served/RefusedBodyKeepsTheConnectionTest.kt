package io.github.matthewjones372.pelican.uring.served

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.uring.handledNow
import io.github.matthewjones372.pelican.uring.start
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.net.Socket

/**
 * Pekko's `ChunkedBodyLimitTest` check, on io_uring: an over-limit chunked body is refused on a connection that is
 * still usable. A raw socket, because a client that opens a second connection would pass either way.
 */
class RefusedBodyKeepsTheConnectionTest {

    data class Jotted(val text: String)

    companion object {
        private val notes = endpoint(jsonBody<Jotted>()) {
            post("notes")
            text()
        }

        private val server = api(listOf(notes handledNow { "read ${it.text.length}" }), JacksonCodecs) {
            maxBodyBytes = 1_024
        }.start(port = 0)

        @JvmStatic
        @AfterAll
        fun stop() = server.stop()

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
