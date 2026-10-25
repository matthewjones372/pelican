package example.backends

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.BufferedReader
import java.io.OutputStream
import java.net.Socket
import java.net.URI

/**
 * Spec 0052: a response that completes while the client is still sending the body leaves the connection usable.
 * Each body is sent only once the answer has been read, so only a server that reads it can pass. A raw socket,
 * because a client that opened a second connection would pass either way.
 *
 * Pekko is left out: it answers both cases with `Connection: close` and closes, which this suite does not yet accept.
 */
class UnreadBodyTest {

    companion object {
        private val running: Map<String, Running> =
            runnableBackends.filter { it !== OnPekko }.associate { it.name to it.start(port = 0) }

        @JvmStatic
        fun backends(): List<Array<Any>> = running.map { (name, server) -> arrayOf<Any>(name, server.baseUrl) }

        @JvmStatic
        @AfterAll
        fun stopAll() = running.values.forEach { it.stop() }

        private const val LAST_CHUNK = "0\r\n\r\n"

        private const val PARTS =
            "--B\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\nNotes\r\n" +
                "--B\r\nContent-Disposition: form-data; name=\"notes\"; filename=\"n\"\r\n" +
                "Content-Type: text/plain\r\n\r\nshort\r\n" +
                "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f\"\r\n" +
                "Content-Type: text/plain\r\n\r\nhello\r\n--B--\r\n"

        /** Long enough that a hung read fails the test rather than the whole run. */
        private const val SOCKET_TIMEOUT_MILLIS = 5_000
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `a body still arriving when it is refused is read, on a connection that is still usable`(
        name: String,
        baseUrl: String,
    ) = onOneSocket(baseUrl) { out, reader ->
        // A multipart type naming no boundary is refused before a byte of the body is read.
        out.write(uploadHead("multipart/form-data").toByteArray())
        out.flush()
        val refusal = readHead(reader)

        withClue("$name: $refusal") {
            refusal.first() shouldStartWith "HTTP/1.1 400 "
            refusal.none { it.equals("Connection: close", ignoreCase = true) } shouldBe true
        }
        drainBody(reader, refusal)

        out.write((chunk(PARTS) + LAST_CHUNK + GREET).toByteArray())
        out.flush()

        withClue("$name: the same socket, no reconnection") { readHead(reader).first() shouldBe "HTTP/1.1 200 OK" }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("backends")
    fun `an upload read to its closing boundary is read to its end, on a connection that is still usable`(
        name: String,
        baseUrl: String,
    ) = onOneSocket(baseUrl) { out, reader ->
        out.write((uploadHead("multipart/form-data; boundary=B") + chunk(PARTS)).toByteArray())
        out.flush()
        val answer = readHead(reader)

        withClue("$name: $answer") {
            answer.first() shouldBe "HTTP/1.1 200 OK"
            answer.none { it.equals("Connection: close", ignoreCase = true) } shouldBe true
        }
        drainBody(reader, answer)

        out.write((LAST_CHUNK + GREET).toByteArray())
        out.flush()

        withClue("$name: the same socket, no reconnection") { readHead(reader).first() shouldBe "HTTP/1.1 200 OK" }
    }

    private fun onOneSocket(baseUrl: String, exchange: (OutputStream, BufferedReader) -> Unit) {
        val uri = URI.create(baseUrl)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            exchange(socket.getOutputStream(), socket.getInputStream().bufferedReader())
        }
    }

    private fun uploadHead(contentType: String): String =
        "POST /upload HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Content-Type: $contentType\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n"

    private fun chunk(text: String): String = Integer.toHexString(text.toByteArray().size) + "\r\n" + text + "\r\n"

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

private const val GREET = "GET /hello/ada HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
