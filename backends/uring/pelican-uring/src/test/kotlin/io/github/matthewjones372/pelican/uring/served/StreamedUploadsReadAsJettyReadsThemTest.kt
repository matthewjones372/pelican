package io.github.matthewjones372.pelican.uring.served

import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.filePart
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ndjsonIn
import io.github.matthewjones372.pelican.uring.READ_TIMEOUT_MILLIS
import io.github.matthewjones372.pelican.uring.handledNow
import io.github.matthewjones372.pelican.uring.reader
import io.github.matthewjones372.pelican.uring.start
import io.github.matthewjones372.pelican.uring.toSequence
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.github.matthewjones372.pelican.jetty.handledNow as jettyHandledNow
import io.github.matthewjones372.pelican.jetty.reader as jettyReader
import io.github.matthewjones372.pelican.jetty.start as jettyStart
import io.github.matthewjones372.pelican.jetty.toSequence as jettyToSequence

data class Tally(val count: Int)

private val THREE_FRAMES = listOf("Hello", "Bonjour", "Hola").joinToString("") { """{"text":"$it"}""" + "\n" }

/** NDJSON uploads on both servers, asked the same questions over a socket. */
class StreamedUploadsReadAsJettyReadsThemTest {

    data class Seen(val status: Int, val contentType: String?, val body: String)

    data class Ask(val name: String, val body: String, val chunked: Boolean) {
        override fun toString() = "$name${if (chunked) " chunked" else ""}"
    }

    companion object {
        private val tally = endpoint(ndjsonIn<Note>()) {
            post("tally")
            json<Tally>()
        }

        private val gate = endpoint(ndjsonIn<Note>()) {
            post("gate")
            json<Tally>()
        }

        private val first = endpoint(ndjsonIn<Note>()) {
            post("first")
            json<Note>()
        }

        private val file = filePart("file")
        private val stalled = endpoint(file) {
            post("stalled")
            text()
        }

        private val uringReached = CountDownLatch(1)
        private val jettyReached = CountDownLatch(1)

        private fun served(vararg endpoints: ServerEndpoint) =
            api(endpoints.toList(), JacksonCodecs) { maxBodyBytes = 1024 }

        private val uring = served(
            tally handledNow { rows -> Tally(rows.toSequence().count()) },
            gate handledNow { rows ->
                Tally(rows.toSequence().onEach { uringReached.countDown() }.count())
            },
            first handledNow { rows -> rows.reader().use { it.next() } },
            stalled handledNow { file -> "read ${file.bytes().size}" },
        ).start(port = 0)

        private val jetty = served(
            tally jettyHandledNow { rows -> Tally(rows.jettyToSequence().count()) },
            gate jettyHandledNow { rows ->
                Tally(rows.jettyToSequence().onEach { jettyReached.countDown() }.count())
            },
            first jettyHandledNow { rows -> rows.jettyReader().use { it.next() } },
            stalled jettyHandledNow { file -> "read ${file.bytes().size}" },
        ).jettyStart(port = 0)

        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stopBoth() {
            client.close()
            uring.stop()
            jetty.stop()
        }

        @JvmStatic
        fun servers(): List<String> = listOf("uring", "jetty")

        @JvmStatic
        fun asks(): List<Ask> = listOf(false, true).flatMap { chunked ->
            fun ask(name: String, body: String) = Ask(name, body, chunked)
            listOf(
                ask("three frames", THREE_FRAMES),
                ask("no newline after the last", """{"text":"Hello"}""" + "\n" + """{"text":"Hola"}"""),
                ask("blank lines", "\n" + """{"text":"Hello"}""" + "\n\n" + """{"text":"Hola"}""" + "\n\n"),
                ask("empty", ""),
                ask("undecodable frame", """{"text":"fine"}""" + "\nnot json at all\n"),
                ask("frame that is not a note", """{"text":"fine"}""" + "\n\n" + """{"words":3}""" + "\n"),
                ask("frame past maxFrameBytes", """{"text":"""" + "x".repeat(5_000) + "\"}\n"),
            )
        }
    }

    private fun send(baseUrl: String, ask: Ask, path: String = "/tally"): Seen {
        val bytes = ask.body.toByteArray()
        val publisher =
            if (ask.chunked) HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) }
            else HttpRequest.BodyPublishers.ofByteArray(bytes)
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Content-Type", "application/x-ndjson")
            .POST(publisher)
            .build()
        val res = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Seen(res.statusCode(), res.headers().firstValue("Content-Type").orElse(null), res.body())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("asks")
    fun `uring reads a streamed upload as jetty does`(ask: Ask) {
        withClue(ask) { send(uring.baseUrl, ask) shouldBe send(jetty.baseUrl, ask) }
    }

    @Test
    fun `a streamed upload reaches the handler as the frames it was written as`() {
        val seen = send(uring.baseUrl, asks().first())

        seen shouldBe Seen(200, "application/json", """{"count":3}""")
    }

    @Test
    fun `a frame that will not decode is a 400 naming the line it was on`() {
        val seen = send(uring.baseUrl, asks().first { it.name == "undecodable frame" })

        withClue(seen) {
            seen.status shouldBe 400
            seen.body shouldContain "Frame 2"
        }
    }

    @Test
    fun `a frame larger than the service will hold is a 413`() {
        send(uring.baseUrl, asks().first { it.name == "frame past maxFrameBytes" }).status shouldBe 413
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("servers")
    fun `the handler holds the first frame before the last one is sent`(server: String) {
        val (baseUrl, reached) = if (server == "uring") uring.baseUrl to uringReached else jetty.baseUrl to jettyReached
        openUpload(baseUrl, "/gate").use { socket ->
            val out = socket.getOutputStream()
            out.chunk("""{"text":"first"}""" + "\n")

            withClue("the handler did not see the first frame while the upload was still open") {
                reached.await(30, TimeUnit.SECONDS) shouldBe true
            }

            out.chunk("""{"text":"last"}""" + "\n")
            out.ascii("0\r\n\r\n")

            String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8) shouldContain """{"count":2}"""
        }
    }

    @Test
    fun `a reader closed after one frame answers while the rest of the upload is unsent`() {
        openUpload(uring.baseUrl, "/first").use { socket ->
            socket.getOutputStream().chunk("""{"text":"only"}""" + "\n")

            val answer = String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8)

            answer shouldStartWith "HTTP/1.1 200"
            answer shouldContain """{"text":"only"}"""
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("servers")
    fun `an upload that stops arriving is given up on after jetty's read timeout`(server: String) {
        val baseUrl = if (server == "uring") uring.baseUrl else jetty.baseUrl
        val started = System.nanoTime()
        openUpload(baseUrl, "/stalled", "multipart/form-data; boundary=B").use { socket ->
            val opened = "--B\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f\"\r\n\r\nx"
            socket.getOutputStream().chunk(opened)

            val answer = String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8)

            answer shouldStartWith "HTTP/1.1 500"
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) shouldBeGreaterThanOrEqual READ_TIMEOUT_MILLIS
        }
    }

    private fun openUpload(baseUrl: String, path: String, type: String = "application/x-ndjson"): Socket {
        val uri = URI.create(baseUrl)
        val socket = Socket(uri.host, uri.port)
        socket.getOutputStream().ascii(
            "POST $path HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n" +
                "Content-Type: $type\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n",
        )
        return socket
    }

    private fun OutputStream.ascii(text: String) {
        write(text.toByteArray(StandardCharsets.US_ASCII))
        flush()
    }

    private fun OutputStream.chunk(text: String) = ascii("${text.length.toString(16)}\r\n$text\r\n")
}
