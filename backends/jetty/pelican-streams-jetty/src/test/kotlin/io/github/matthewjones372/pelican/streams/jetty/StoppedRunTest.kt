package io.github.matthewjones372.pelican.streams.jetty

import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.Running
import io.github.matthewjones372.surefold.stream.runFold
import io.github.matthewjones372.surefold.stream.start
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.handledBy
import io.github.matthewjones372.pelican.jetty.request
import io.github.matthewjones372.pelican.jetty.start
import io.github.matthewjones372.pelican.ndjsonIn
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.eclipse.jetty.io.Content
import org.junit.jupiter.api.Test
import java.io.OutputStream
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

/** A run stopped while its read waits on an upload the client is still holding open. */
class StoppedRunTest {

    data class Note(val text: String)

    data class Tally(val counted: Int)

    private val held = endpoint(ndjsonIn<Note>()) {
        post("held")
        json<Tally>()
    }

    @Test
    fun `a stopped run closes the upload, waking the read it was blocked in`() {
        val folded = CountDownLatch(1)
        val reading = CompletableFuture<Thread>()
        val running = CompletableFuture<Running<Nothing, Int>>()
        val exit = CompletableFuture<Exit<Nothing, Int>>()
        val released = CompletableFuture<Boolean>()
        val app = api(
            endpoints = listOf(
                held handledBy { rows ->
                    val run = rows.toStream()
                        .runFold(0) { seen, _ ->
                            reading.complete(Thread.currentThread())
                            folded.countDown()
                            seen + 1
                        }
                        .start(Forks())
                    running.complete(run)
                    run.exit.thenApply { ended ->
                        exit.complete(ended)
                        // The request itself, read after the run: closed by the run, it answers with a failure.
                        released.complete(Content.Chunk.isFailure(request.read()))
                        Tally(ended.valueOr(-1))
                    }
                },
            ),
            codecs = JacksonCodecs,
        )

        app.start(port = 0).use { server ->
            openUpload(server.baseUrl, "/held").use { socket ->
                socket.getOutputStream().chunk("""{"text":"first"}""" + "\n")
                folded.await()
                val loop = reading.join()
                while (loop.state != Thread.State.WAITING && loop.state != Thread.State.TIMED_WAITING) {
                    Thread.onSpinWait()
                }

                running.join().stop()

                exit.join().shouldBeInstanceOf<Exit.Done<Int>>().value shouldBe 1
                withClue("the run ended without closing the upload it was reading") { released.join() shouldBe true }
                val answer = socket.answer()
                answer shouldStartWith "HTTP/1.1 200"
                answer shouldContain """{"counted":1}"""
            }
        }
    }

    private fun openUpload(baseUrl: String, path: String): Socket {
        val uri = URI.create(baseUrl)
        val socket = Socket(uri.host, uri.port)
        socket.getOutputStream().ascii(
            "POST $path HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n" +
                "Content-Type: application/x-ndjson\r\nTransfer-Encoding: chunked\r\n\r\n",
        )
        return socket
    }

    /** Reads until the body has arrived, without waiting for the server to close the socket. */
    private fun Socket.answer(): String {
        val input = getInputStream()
        val seen = StringBuilder()
        while (!seen.endsWith("}") && !seen.endsWith("}\r\n0\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) break
            seen.append(byte.toChar())
        }
        return seen.toString()
    }

    private fun OutputStream.ascii(text: String) {
        write(text.toByteArray(StandardCharsets.US_ASCII))
        flush()
    }

    private fun OutputStream.chunk(text: String) = ascii("${text.length.toString(16)}\r\n$text\r\n")
}
