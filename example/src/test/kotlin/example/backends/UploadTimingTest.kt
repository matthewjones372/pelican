package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ndjsonIn
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.map
import org.junit.jupiter.api.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import io.github.matthewjones372.pelican.http4k.start as startOnHttp4k
import io.github.matthewjones372.pelican.http4k.streamedNow as streamedNowOnHttp4k
import io.github.matthewjones372.pelican.http4k.toSequence as toSequenceOnHttp4k
import io.github.matthewjones372.pelican.ktor.start as startOnKtor
import io.github.matthewjones372.pelican.ktor.streamedNow as streamedNowOnKtor
import io.github.matthewjones372.pelican.ktor.toFlow as toFlowOnKtor
import io.github.matthewjones372.pelican.pekko.start as startOnPekko
import io.github.matthewjones372.pelican.pekko.streamedNow as streamedNowOnPekko
import io.github.matthewjones372.pelican.pekko.toSource as toSourceOnPekko

/**
 * A typed upload is read as it arrives, which is the whole claim `ndjsonIn`
 * makes over reading a `rawBody()` by hand.
 *
 * The measurement is the only one that can be made without believing the
 * server: one frame is written, the rest of the upload is *not*, and the answer
 * to the first frame is read off the socket. A server that buffered the request
 * would still be waiting for the terminating chunk, and the read below would
 * time out instead.
 *
 * A raw socket rather than a client, because an HTTP client is free to gather
 * a chunked body before handing it over and either behaviour would make this a
 * test of the client. Same reason `SlowConsumerTest` uses one.
 */
class UploadTimingTest {

    private companion object {
        /** Long enough that a buffering server fails rather than merely being slow. */
        const val SOCKET_TIMEOUT_MILLIS = 5_000
    }

    private val relay = endpoint(ndjsonIn<Note>()) {
        post("relay")
        operationId = "relay"
        ndjson<Note>()
    }

    private fun api(route: ServerEndpoint): Api = api(endpoints = listOf(route), codecs = JacksonCodecs)

    @Test
    fun `pekko answers the first frame before the last one is sent`() {
        val server = api(relay streamedNowOnPekko { rows -> rows.toSourceOnPekko() })
            .startOnPekko(port = 0, systemName = "upload-timing")

        try {
            server.baseUrl.shouldAnswerBeforeTheUploadEnds()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `http4k answers the first frame before the last one is sent`() {
        val server = api(relay streamedNowOnHttp4k { rows -> rows.toSequenceOnHttp4k() }).startOnHttp4k(port = 0)

        try {
            server.baseUrl.shouldAnswerBeforeTheUploadEnds()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `and ktor answers the first frame before the last one is sent`() {
        val server = api(relay streamedNowOnKtor { rows -> rows.toFlowOnKtor().map { it } }).startOnKtor(port = 0)

        try {
            server.baseUrl.shouldAnswerBeforeTheUploadEnds()
        } finally {
            server.stop()
        }
    }

    /**
     * Writes one frame, reads its answer, and only then writes the rest. The
     * upload is chunked, so nothing about the request says how long it is and
     * the server has no excuse for waiting.
     */
    private fun String.shouldAnswerBeforeTheUploadEnds() {
        val uri = URI.create(this)
        Socket(uri.host, uri.port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val out = socket.getOutputStream()

            out.write(
                (
                    "POST /relay HTTP/1.1\r\n" +
                        "Host: ${uri.host}:${uri.port}\r\n" +
                        "Content-Type: application/x-ndjson\r\n" +
                        "Transfer-Encoding: chunked\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII),
            )
            out.chunk("""{"text":"first"}""" + "\n")

            val answered = socket.getInputStream().readUntil("first")
            withClue("the first frame's answer never arrived; the upload was still open") {
                answered shouldContain "first"
            }

            out.chunk("""{"text":"last"}""" + "\n")
            out.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            out.flush()

            socket.getInputStream().readUntil("last") shouldContain "last"
        }
    }

    private fun OutputStream.chunk(text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        write(bytes.size.toString(RADIX_HEX).toByteArray(StandardCharsets.US_ASCII))
        write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        write(bytes)
        write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        flush()
    }

    /** Reads until [marker] shows up, or the socket's own timeout gives up. */
    private fun InputStream.readUntil(marker: String): String {
        val buffer = ByteArray(BUFFER_BYTES)
        val seen = StringBuilder()
        while (!seen.contains(marker)) {
            val read = read(buffer)
            check(read >= 0) { "the response ended without '$marker': $seen" }
            seen.append(String(buffer, 0, read, StandardCharsets.UTF_8))
        }
        return seen.toString()
    }
}

private const val BUFFER_BYTES = 512
private const val RADIX_HEX = 16
