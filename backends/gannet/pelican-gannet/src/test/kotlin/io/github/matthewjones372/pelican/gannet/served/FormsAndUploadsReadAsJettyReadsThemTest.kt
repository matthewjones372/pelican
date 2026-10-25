package io.github.matthewjones372.pelican.gannet.served

import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.StringCodec
import io.github.matthewjones372.pelican.UploadedFile
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.bufferedFile
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.filePart
import io.github.matthewjones372.pelican.formBody
import io.github.matthewjones372.pelican.gannet.RequiresGannet
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.start
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.nonEmpty
import io.github.matthewjones372.pelican.textPart
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import io.github.matthewjones372.pelican.jetty.start as jettyStart

data class Login(val user: String, val remember: Boolean, val visits: Int)

data class Upload(val caption: String, val notes: String, val filename: String?, val type: String?, val content: String)

/** Url-encoded forms and multipart uploads on both servers, asked the same questions over a socket. */
@RequiresGannet
class FormsAndUploadsReadAsJettyReadsThemTest {

    data class Seen(val status: Int, val contentType: String?, val body: String)

    data class Ask(
        val name: String,
        val path: String,
        val contentType: String,
        val body: String,
        val chunked: Boolean,
    ) {
        override fun toString() = "$name${if (chunked) " chunked" else ""}"
    }

    companion object {
        private const val BOUNDARY = "PelicanBoundary"

        private val login = endpoint(formBody<Login>()) {
            post("login")
            json<Login>()
        }

        private val caption = textPart("caption", StringCodec.nonEmpty())
        private val notes = bufferedFile("notes", maxBytes = 512, contentType = "text/plain")
        private val file = filePart("file", contentType = "text/plain")

        private val upload = endpoint(caption, notes, file) {
            post("upload")
            json<Upload>()
        }

        private val reached = CountDownLatch(1)
        private val gate = endpoint(file) {
            post("gate")
            text()
        }

        private fun uploaded(caption: String, notes: UploadedFile, file: UploadedFile) =
            Upload(caption, notes.text(), file.filename, file.contentType, file.text())

        private fun served(vararg endpoints: ServerEndpoint) =
            api(endpoints.toList(), JacksonCodecs) { maxBodyBytes = 1024 }

        private val gannet = served(
            login handledNow { it },
            upload handledNow { (caption, notes, file) -> uploaded(caption, notes, file) },
            gate handledNow { file ->
                reached.countDown()
                "read ${file.bytes().size}"
            },
        ).start(port = 0)

        private val jetty = served(
            login jettyHandledNow { it },
            upload jettyHandledNow { (caption, notes, file) -> uploaded(caption, notes, file) },
        ).jettyStart(port = 0)

        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stopBoth() {
            client.close()
            gannet.stop()
            jetty.stop()
        }

        private fun part(name: String, content: String, filename: String? = null, type: String? = null) =
            "--$BOUNDARY\r\nContent-Disposition: form-data; name=\"$name\"" +
                filename?.let { "; filename=\"$it\"" }.orEmpty() + "\r\n" +
                type?.let { "Content-Type: $it\r\n" }.orEmpty() + "\r\n$content\r\n"

        private fun envelope(vararg parts: String) = parts.joinToString("") + "--$BOUNDARY--\r\n"

        private val multipart = "multipart/form-data; boundary=$BOUNDARY"
        private const val FORM = "application/x-www-form-urlencoded"

        @JvmStatic
        fun asks(): List<Ask> = listOf(false, true).flatMap { chunked ->
            fun form(name: String, body: String) = Ask(name, "/login", FORM, body, chunked)
            fun upload(name: String, body: String, type: String = multipart) = Ask(name, "/upload", type, body, chunked)
            val notesPart = part("notes", "a poem", "about.txt", "text/plain")
            val filePart = part("file", "line one\nline two", "notes.txt", "text/plain")
            val bigFile = part("file", "x".repeat(10_000), "big.txt")
            val bigNotes = part("notes", "x".repeat(600), "n.txt")
            listOf(
                form("typed form", "user=ada&remember=true&visits=3"),
                form("browser form", "user=ada&remember=on&visits=3"),
                form("undecodable field", "user=ada&remember=on&visits=lots"),
                form("unknown fields", "user=ada&remember=on&visits=1&_csrf=deadbeef&submit=Sign+in"),
                form("missing field", "user=ada"),
                form("over limit", "user=${"a".repeat(2_000)}&remember=on&visits=1"),
                upload("upload", envelope(part("caption", "The notes"), notesPart, filePart)),
                upload("file past the limit", envelope(part("caption", "Big"), notesPart, bigFile)),
                upload("buffered part over its bound", envelope(part("caption", "c"), bigNotes, filePart)),
                upload("text parts over the budget", envelope(part("caption", "c".repeat(2_000)), notesPart, filePart)),
                upload("text part after the file", envelope(notesPart, filePart, part("caption", "Too late"))),
                upload("refused text part", envelope(part("caption", ""), notesPart, filePart)),
                upload("missing part", envelope(part("caption", "c"), filePart)),
                upload("unknown part skipped", envelope(part("caption", "c"), part("extra", "?"), notesPart, filePart)),
                upload("not multipart", """{"caption":"x"}""", "application/json"),
                upload("no boundary", envelope(part("caption", "c")), "multipart/form-data"),
                upload("unterminated envelope", part("caption", "c") + "--$BOUNDARY\r\nContent-Disp"),
            )
        }
    }

    private fun send(baseUrl: String, ask: Ask): Seen {
        val bytes = ask.body.toByteArray()
        val publisher =
            if (ask.chunked) HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) }
            else HttpRequest.BodyPublishers.ofByteArray(bytes)
        val request = HttpRequest.newBuilder(URI.create(baseUrl + ask.path))
            .header("Content-Type", ask.contentType)
            .POST(publisher)
            .build()
        val res = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Seen(res.statusCode(), res.headers().firstValue("Content-Type").orElse(null), res.body())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("asks")
    fun `gannet reads a form or an upload as jetty does`(ask: Ask) {
        withClue(ask) { send(gannet.baseUrl, ask) shouldBe send(jetty.baseUrl, ask) }
    }

    @Test
    fun `an upload reaches the handler with its name, filename, media type and bytes`() {
        val notesPart = part("notes", "a poem", "about.txt", "text/plain")
        val body = envelope(part("caption", "The notes"), notesPart, part("file", "line one", "n.txt", "text/plain"))

        send(gannet.baseUrl, Ask("upload", "/upload", multipart, body, chunked = true)) shouldBe Seen(
            200,
            "application/json",
            """{"caption":"The notes","notes":"a poem","filename":"n.txt","type":"text/plain","content":"line one"}""",
        )
    }

    @Test
    fun `a form field that will not decode is a 400 naming the field`() {
        val seen = send(gannet.baseUrl, Ask("bad", "/login", FORM, "user=ada&remember=on&visits=lots", false))

        seen.status shouldBe 400
        seen.body shouldContain "visits"
    }

    @Test
    fun `the handler holds a streamed file before the upload has finished arriving`() {
        val uri = URI.create(gannet.baseUrl)
        Socket(uri.host, uri.port).use { socket ->
            val out = socket.getOutputStream()
            out.ascii(
                "POST /gate HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n" +
                    "Content-Type: $multipart\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n",
            )
            out.chunk("--$BOUNDARY\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f\"\r\n\r\n")
            out.chunk("x".repeat(4_000))

            withClue("the handler was not called while the upload was still open") {
                reached.await(30, TimeUnit.SECONDS) shouldBe true
            }

            out.chunk("y".repeat(4_000) + "\r\n--$BOUNDARY--\r\n")
            out.ascii("0\r\n\r\n")

            String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8) shouldContain "read 8000"
        }
    }

    private fun OutputStream.ascii(text: String) {
        write(text.toByteArray(StandardCharsets.US_ASCII))
        flush()
    }

    private fun OutputStream.chunk(text: String) = ascii("${text.length.toString(16)}\r\n$text\r\n")
}
