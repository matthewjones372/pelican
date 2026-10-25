package io.github.matthewjones372.pelican.gannet.served

import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.formBody
import io.github.matthewjones372.pelican.gannet.RequiresGannet
import io.github.matthewjones372.pelican.gannet.bytesNow
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.start
import io.github.matthewjones372.pelican.gannet.toSequence
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.or
import io.github.matthewjones372.pelican.rawBody
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.github.matthewjones372.pelican.jetty.bytesNow as jettyBytesNow
import io.github.matthewjones372.pelican.jetty.handledNow as jettyHandledNow
import io.github.matthewjones372.pelican.jetty.start as jettyStart
import io.github.matthewjones372.pelican.jetty.toSequence as jettyToSequence

data class Note(val text: String)

/** Request bodies in and opaque bytes out on both servers, asked the same questions over a socket. */
@RequiresGannet
class BodiesReadAsJettyReadsThemTest {

    /** What a caller can see of an answer, and so what the two servers are compared on. */
    data class Seen(val status: Int, val contentType: String?, val body: String)

    /** One request, sent with a declared length or chunked with none. */
    data class Ask(val path: String, val contentType: String, val body: String, val chunked: Boolean) {
        override fun toString() = "$path $contentType ${body.take(24)}${if (chunked) " chunked" else ""}"
    }

    companion object {
        private val notes = endpoint(jsonBody<Note>()) {
            post("notes")
            json<Note>()
        }

        private val either = endpoint(jsonBody<Note>() or formBody<Note>()) {
            post("either")
            text()
        }

        private val echo = endpoint(rawBody()) {
            post("echo")
            bytes()
        }

        private val logo = endpoint {
            get("logo")
            bytes("image/png")
        }

        private val pngHead = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

        private val big = "x".repeat(4_000)

        private fun served(endpoints: List<ServerEndpoint>) = api(endpoints, JacksonCodecs) { maxBodyBytes = 1024 }

        private val gannet = served(
            listOf(
                notes handledNow { note -> Note(note.text.reversed()) },
                either handledNow { note -> "read ${note.text}" },
                echo bytesNow { body -> body.toSequence() },
                logo bytesNow { sequenceOf(pngHead, "logo".toByteArray()) },
            ),
        ).start(port = 0)

        private val jetty = served(
            listOf(
                notes jettyHandledNow { note -> Note(note.text.reversed()) },
                either jettyHandledNow { note -> "read ${note.text}" },
                echo jettyBytesNow { body -> body.jettyToSequence() },
                logo jettyBytesNow { sequenceOf(pngHead, "logo".toByteArray()) },
            ),
        ).jettyStart(port = 0)

        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stopBoth() {
            client.close()
            gannet.stop()
            jetty.stop()
        }

        @JvmStatic
        fun asks(): List<Ask> = listOf(false, true).flatMap { chunked ->
            listOf(
                Ask("/notes", "application/json", """{"text":"hello"}""", chunked),
                Ask("/notes", "application/json", """{"text":"$big"}""", chunked),
                Ask("/notes", "application/json", """{"text":""", chunked),
                Ask("/notes", "text/plain", "hello", chunked),
                Ask("/either", "application/json", """{"text":"json"}""", chunked),
                Ask("/either", "application/x-www-form-urlencoded", "text=form", chunked),
                Ask("/echo", "application/octet-stream", big, chunked),
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
        return seen(client.send(request, HttpResponse.BodyHandlers.ofString()))
    }

    private fun get(baseUrl: String, path: String): Seen {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path)).build()
        return seen(client.send(request, HttpResponse.BodyHandlers.ofString()))
    }

    private fun seen(res: HttpResponse<String>) =
        Seen(res.statusCode(), res.headers().firstValue("Content-Type").orElse(null), res.body())

    @ParameterizedTest(name = "{0}")
    @MethodSource("asks")
    fun `gannet reads a body as jetty does`(ask: Ask) {
        withClue(ask) { send(gannet.baseUrl, ask) shouldBe send(jetty.baseUrl, ask) }
    }

    @Test
    fun `a chunked body over the limit is refused, though nothing declared its length`() {
        val seen = send(gannet.baseUrl, Ask("/notes", "application/json", """{"text":"$big"}""", chunked = true))

        seen.status shouldBe 413
        seen.body shouldContain "Payload too large"
    }

    @Test
    fun `a chunked body past the overrun is refused without being read whole`() {
        val huge = """{"text":"${"x".repeat(200_000)}"}"""
        val seen = send(gannet.baseUrl, Ask("/notes", "application/json", huge, chunked = true))

        seen.status shouldBe 413
        seen.body shouldContain "Payload too large"
    }

    @Test
    fun `a json body round-trips`() {
        send(gannet.baseUrl, Ask("/notes", "application/json", """{"text":"hello"}""", chunked = false)) shouldBe
            Seen(200, "application/json", """{"text":"olleh"}""")
    }

    @Test
    fun `a raw body arrives whole and unread, past the limit a strict body is held to`() {
        send(gannet.baseUrl, Ask("/echo", "application/octet-stream", big, chunked = true)) shouldBe
            Seen(200, "application/octet-stream", big)
    }

    @Test
    fun `opaque bytes keep their declared media type`() {
        val seen = get(gannet.baseUrl, "/logo")

        seen shouldBe get(jetty.baseUrl, "/logo")
        seen.contentType shouldBe "image/png"
    }
}
