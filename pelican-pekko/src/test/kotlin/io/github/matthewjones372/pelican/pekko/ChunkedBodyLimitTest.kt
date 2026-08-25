package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.pekko.actor.testkit.typed.annotations.JUnit5TestKit
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.javadsl.JUnit5TestKitBuilder
import org.apache.pekko.actor.testkit.typed.javadsl.TestKitJUnit5Extension
import org.apache.pekko.http.javadsl.Http
import org.apache.pekko.http.javadsl.ServerBinding
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A body with no declared length is still refused when it is too big.
 *
 * The interpreter reads a strict body two ways. A request that declares a
 * Content-Length has that number checked before anything is read, and is then
 * read with Pekko's plain `toStrict` — the size-limited variant materialises a
 * limiting stage in front of the entity, which measured at about six
 * microseconds a request, and there is nothing for it to catch once the
 * declared length has been refused.
 *
 * A chunked request declares nothing, so it gets the limiting stage, and that
 * is the only thing standing between the service and an unbounded read. This
 * test is what stops the two branches being collapsed back together by
 * somebody who reads the fast one and assumes it is the whole story.
 *
 * `BodyPublishers.ofInputStream` is how the JDK client is made to send chunked
 * with no Content-Length: it cannot know the length in advance either.
 *
 * `@TestInstance(PER_CLASS)` is what makes one kit, and so one binding, serve
 * both tests.
 */
@ExtendWith(TestKitJUnit5Extension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChunkedBodyLimitTest {

    data class Note(val text: String)

    /** Found by reflection, so the field has to be a real one: `@JvmField`. */
    @JUnit5TestKit
    @JvmField
    val testKit: ActorTestKit = JUnit5TestKitBuilder().withName("chunked-limit-test").build()

    private val note = jsonBody<Note>()

    private val echo = endpoint(note) {
        post("notes")
        text()
    }

    /** A deliberately tiny ceiling, so the test does not have to send megabytes. */
    private val api = Api(
        endpoints = listOf(echo handledNow { body -> "read ${body.text.length}" }),
        codecs = JacksonCodecs,
        maxBodyBytes = 1024,
    )

    // Unbound by the testkit's shutdown, which runs the system's coordinated
    // shutdown, and that is what Pekko HTTP hooks its own unbind onto.
    private val binding: ServerBinding = Http.get(testKit.system())
        .newServerAt("127.0.0.1", 0)
        .bind(api.toRoute(testKit.system()))
        .toCompletableFuture()
        .join()

    private fun postChunked(body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${binding.localAddress().port}/notes"))
            .header("Content-Type", "application/json")
            // No length: the publisher is a stream, so the client chunks it.
            .POST(HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(body.toByteArray()) })
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `a chunked body over the limit is refused, though nothing declared its length`() {
        val res = postChunked("""{"text":"${"x".repeat(4_000)}"}""")

        res.statusCode() shouldBe 413
        withClue(res.body()) { res.body() shouldContain "Payload too large" }
        // Refused rather than decoded: none of the payload comes back.
        withClue(res.body().take(200)) { res.body() shouldNotContain "xxxx" }
    }

    @Test
    fun `a chunked body under the limit is read as normal`() {
        val res = postChunked("""{"text":"hello"}""")

        res.statusCode() shouldBe 200
        res.body() shouldBe "read 5"
    }

    /**
     * The refusal is only worth anything if the client gets to read it.
     *
     * A chunked upload is still being written when the limit is reached, so
     * cutting the entity off there leaves unread bytes on the connection —
     * and a connection with unread bytes is one Pekko HTTP answers on and
     * then closes. On Linux that close reaches a client mid-write as a broken
     * pipe, and the 413 it was about to read is gone: this test failed in CI
     * as an `IOException` out of `HttpClient.send`, not as a wrong status.
     *
     * So the reader takes a bounded overrun past the limit, and this is what
     * says so, in the only terms that distinguish the two: the refusal comes
     * back without `Connection: close`, and the next request on the same
     * socket is answered. A raw socket rather than `HttpClient`, because a
     * client that transparently opens a second connection would pass either
     * way and prove nothing.
     */
    @Test
    fun `the refusal arrives on a connection that is still usable`() {
        Socket("127.0.0.1", binding.localAddress().port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val out = socket.getOutputStream()
            val reader = socket.getInputStream().bufferedReader()

            out.write(chunkedRequest("""{"text":"${"x".repeat(4_000)}"}""").toByteArray())
            out.flush()
            val refusal = readHead(reader)

            withClue(refusal) {
                refusal.first() shouldBe "HTTP/1.1 413 Content Too Large"
                refusal.none { it.equals("Connection: close", ignoreCase = true) } shouldBe true
            }
            drainBody(reader, refusal)

            // The same socket, no reconnection: the refusal cost the request,
            // not the connection.
            out.write(chunkedRequest("""{"text":"hello"}""").toByteArray())
            out.flush()
            val answer = readHead(reader)

            withClue(answer) { answer.first() shouldBe "HTTP/1.1 200 OK" }
        }
    }

    /** Chunked and length-free, the same as `postChunked` sends. */
    private fun chunkedRequest(body: String): String =
        "POST /notes HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Content-Type: application/json\r\n" +
            "Transfer-Encoding: chunked\r\n\r\n" +
            Integer.toHexString(body.toByteArray().size) + "\r\n" + body + "\r\n0\r\n\r\n"

    /** The status line and headers, up to the blank line that ends them. */
    private fun readHead(reader: BufferedReader): List<String> =
        generateSequence { reader.readLine() }
            .takeWhile { it.isNotEmpty() }
            .toList()
            .ifEmpty { error("The server closed the connection instead of answering.") }

    /** Read past the body, so the next response starts where the reader is. */
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

/** Long enough that a hung read fails the test rather than the whole run. */
private const val SOCKET_TIMEOUT_MILLIS = 5_000
