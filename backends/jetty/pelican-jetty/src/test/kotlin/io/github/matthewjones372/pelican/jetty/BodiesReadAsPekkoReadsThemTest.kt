package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.formBody
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.or
import io.github.matthewjones372.pelican.rawBody
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Source
import org.apache.pekko.util.ByteString
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.github.matthewjones372.pelican.pekko.bytesNow as pekkoBytesNow
import io.github.matthewjones372.pelican.pekko.handledNow as pekkoHandledNow
import io.github.matthewjones372.pelican.pekko.start as pekkoStart
import io.github.matthewjones372.pelican.pekko.toSource as pekkoToSource

data class Note(val text: String)

/** Request bodies in and opaque bytes out on both servers, asked the same questions over a socket. */
class BodiesReadAsPekkoReadsThemTest {

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

        private val jetty = served(
            listOf(
                notes handledNow { note -> Note(note.text.reversed()) },
                either handledNow { note -> "read ${note.text}" },
                echo bytesNow { body -> body.toSequence() },
                logo bytesNow { sequenceOf(pngHead, "logo".toByteArray()) },
            ),
        ).start(port = 0)

        private val pekko = served(
            listOf(
                notes pekkoHandledNow { note -> Note(note.text.reversed()) },
                either pekkoHandledNow { note -> "read ${note.text}" },
                echo pekkoBytesNow { body -> body.pekkoToSource().mapMaterializedValue { NotUsed.getInstance() } },
                logo pekkoBytesNow {
                    Source.from(listOf(ByteString.fromArray(pngHead), ByteString.fromString("logo")))
                },
            ),
        ).pekkoStart(port = 0)

        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stopBoth() {
            client.close()
            jetty.stop()
            pekko.stop()
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
    fun `jetty reads a body as pekko does`(ask: Ask) {
        withClue(ask) { send(jetty.baseUrl, ask) shouldBe send(pekko.baseUrl, ask) }
    }

    @Test
    fun `a chunked body over the limit is refused, though nothing declared its length`() {
        val seen = send(jetty.baseUrl, Ask("/notes", "application/json", """{"text":"$big"}""", chunked = true))

        seen.status shouldBe 413
        seen.body shouldContain "Payload too large"
    }

    @Test
    fun `a chunked body past the overrun is refused without being read whole`() {
        val huge = """{"text":"${"x".repeat(200_000)}"}"""
        val seen = send(jetty.baseUrl, Ask("/notes", "application/json", huge, chunked = true))

        seen.status shouldBe 413
        seen.body shouldContain "Payload too large"
    }

    @Test
    fun `a json body round-trips`() {
        send(jetty.baseUrl, Ask("/notes", "application/json", """{"text":"hello"}""", chunked = false)) shouldBe
            Seen(200, "application/json", """{"text":"olleh"}""")
    }

    @Test
    fun `a raw body arrives whole and unread, past the limit a strict body is held to`() {
        send(jetty.baseUrl, Ask("/echo", "application/octet-stream", big, chunked = true)) shouldBe
            Seen(200, "application/octet-stream", big)
    }

    @Test
    fun `opaque bytes keep their declared media type`() {
        val seen = get(jetty.baseUrl, "/logo")

        seen shouldBe get(pekko.baseUrl, "/logo")
        seen.contentType shouldBe "image/png"
    }
}
