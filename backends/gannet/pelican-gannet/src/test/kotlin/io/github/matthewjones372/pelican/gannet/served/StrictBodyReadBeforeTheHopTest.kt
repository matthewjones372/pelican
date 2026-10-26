package io.github.matthewjones372.pelican.gannet.served

import io.github.matthewjones372.pelican.Authenticator
import io.github.matthewjones372.pelican.Identity
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.authenticated
import io.github.matthewjones372.pelican.bearerAuth
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.gannet.Handlers
import io.github.matthewjones372.pelican.gannet.RequiresGannet
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.start
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.queryParam
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Spec 0090: a strict body is read on the carrier before the hop, unless an authenticator has to run first. */
@RequiresGannet
class StrictBodyReadBeforeTheHopTest {

    data class Jotted(val text: String)

    companion object {
        private val bearer = bearerAuth()
        private val caller = authenticated(bearer) { it.subject }

        private val notes = endpoint(queryParam<Int>("n"), jsonBody<Jotted>()) {
            post("notes")
            text()
        }

        private val signed = endpoint(jsonBody<Jotted>()) {
            post("signed")
            authenticatedBy(caller)
            text()
        }

        /** Whether the whole body had been sent when the handler's executor was last asked to run something. */
        private val bodyWholeWhenAsked = AtomicReference<Boolean?>()
        private val bodySent = AtomicBoolean()

        private val recording = Executor { task ->
            bodyWholeWhenAsked.set(bodySent.get())
            Thread.ofVirtual().start(task)
        }

        private val served = api(
            listOf(
                notes handledNow { (n, jotted) -> "$n ${jotted.text}" },
                signed handledNow { jotted -> "${this[caller]} ${jotted.text}" },
            ),
            JacksonCodecs,
        ) { authenticate(bearer, Authenticator { c -> c.bearerToken()?.let(::Identity) }) }

        private val recorded = served.start(port = 0, handlers = Handlers.on(recording))
        private val byDefault = served.start(port = 0)

        @JvmStatic
        @AfterAll
        fun stop() {
            recorded.stop()
            byDefault.stop()
        }

        /** Long enough for the request line and headers to be routed, and the hop taken if it is taken first. */
        private const val PAUSE_MILLIS = 300L
        private const val SOCKET_TIMEOUT_MILLIS = 10_000
        private const val BODY = """{"text":"hello"}"""
    }

    @Test
    fun `the handler's thread is asked for only once the strict body has been read`() {
        sendInHalves("/notes?n=1") shouldStartWith "HTTP/1.1 200 "
        bodyWholeWhenAsked.get() shouldBe true
    }

    @Test
    fun `an endpoint behind an authenticator is moved before its body is read`() {
        sendInHalves("/signed", "Authorization: Bearer ada\r\n") shouldStartWith "HTTP/1.1 200 "
        bodyWholeWhenAsked.get() shouldBe false
    }

    @Test
    fun `a body that will not decode is still answered after a query that will not`() {
        val answer = Asking(emptyList()).use {
            it.ask(byDefault.baseUrl, "POST", "/notes?n=x", mapOf("Content-Type" to "application/json"), "{")
        }
        withClue(answer) {
            answer.status shouldBe 400
            answer.body shouldContain "for 'n'"
        }
    }

    @Test
    fun `a body that will not decode is still answered after an Accept nothing satisfies`() {
        val answer = Asking(emptyList()).use {
            it.ask(
                byDefault.baseUrl,
                "POST",
                "/notes?n=1",
                mapOf("Content-Type" to "application/json", "Accept" to "image/png"),
                "{",
            )
        }
        withClue(answer) { answer.status shouldBe 406 }
    }

    /** The head and half the body, a pause, then the rest: what the executor saw is recorded against [bodySent]. */
    private fun sendInHalves(path: String, headers: String = ""): String {
        bodySent.set(false)
        bodyWholeWhenAsked.set(null)
        Socket("127.0.0.1", recorded.port).use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            val out = socket.getOutputStream()
            val head = "POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n$headers" +
                "Content-Length: ${BODY.length}\r\nConnection: close\r\n\r\n"
            out.write((head + BODY.substring(0, BODY.length / 2)).toByteArray())
            out.flush()
            Thread.sleep(PAUSE_MILLIS)
            bodySent.set(true)
            out.write(BODY.substring(BODY.length / 2).toByteArray())
            out.flush()
            return socket.getInputStream().bufferedReader().readText()
        }
    }
}
