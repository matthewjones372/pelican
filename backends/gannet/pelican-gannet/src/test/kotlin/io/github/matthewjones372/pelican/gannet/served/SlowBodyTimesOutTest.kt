package io.github.matthewjones372.pelican.gannet.served

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.gannet.Handlers
import io.github.matthewjones372.pelican.gannet.RequiresGannet
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.start
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.net.Socket
import java.util.concurrent.TimeUnit

/** A body that stops arriving is a 408 after `strictBodyTimeoutMillis`, wherever the endpoint reads it. */
@RequiresGannet
class SlowBodyTimesOutTest {

    data class Jotted(val text: String)

    companion object {
        private const val TIMEOUT_MILLIS = 300L

        private val notes = endpoint(jsonBody<Jotted>()) {
            post("notes")
            text()
        }

        private val served = api(listOf(notes handledNow { "read ${it.text.length}" }), JacksonCodecs) {
            strictBodyTimeoutMillis = TIMEOUT_MILLIS
        }

        private val onVirtualThreads = served.start(port = 0)
        private val onCarrier = served.start(port = 0, handlers = Handlers.onDispatcher)

        @JvmStatic
        @AfterAll
        fun stop() {
            onVirtualThreads.stop()
            onCarrier.stop()
        }

        /** Long enough that a deadline that never fires fails the test rather than hanging the run. */
        private const val SOCKET_TIMEOUT_MILLIS = 10_000
    }

    @Test
    fun `a stalled body read on a virtual thread is a 408`() = assertTimesOut(onVirtualThreads.port)

    @Test
    fun `a stalled body read on the carrier is a 408`() = assertTimesOut(onCarrier.port)

    @Test
    fun `a body that arrives in time is read whole after a stalled one timed out`() {
        assertTimesOut(onCarrier.port)
        val body = """{"text":"hello"}"""
        Socket("127.0.0.1", onCarrier.port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            socket.getOutputStream().write((head(body.length) + body).toByteArray())
            socket.getInputStream().bufferedReader().readLine() shouldBe "HTTP/1.1 200 OK"
        }
    }

    private fun assertTimesOut(port: Int) {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val started = System.nanoTime()
            socket.getOutputStream().write((head(length = 40) + """{"text":""").toByteArray())
            socket.getOutputStream().flush()

            val answer = socket.getInputStream().bufferedReader().readText()
            val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            withClue(answer) {
                answer shouldStartWith "HTTP/1.1 408 "
                answer shouldContain "Timed out reading the request body"
            }
            withClue("the 408 came before the timeout") { waited shouldBeGreaterThanOrEqual TIMEOUT_MILLIS }
        }
    }

    private fun head(length: Int): String =
        "POST /notes HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: $length\r\n\r\n"
}
