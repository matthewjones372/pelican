package dev.surefold.pelican.test.gannet

import dev.surefold.pelican.api
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.gannet.GannetHandler
import dev.surefold.pelican.gannet.RequiresGannet
import dev.surefold.pelican.gannet.handledNow
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.jsonBody
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

data class Note(val text: String)

private val note = jsonBody<Note>()

private val echo = endpoint(note) {
    post("notes")
    json<Note>()
}

/** Answers with the body and Content-Type it was sent, standing in for the interpreter's body reading. */
private val mirror = GannetHandler { exchange ->
    val request = exchange.request
    val type = request.headers["Content-Type"].orEmpty()
    exchange.respond(200, listOf("Content-Type" to type), request.body.readAll())
    true
}

@RequiresGannet
class HandlerBesideTheApiTest {

    @Test
    fun `a handler the caller builds receives the body the client sent`() {
        api(endpoints = listOf(echo handledNow { it }), codecs = JacksonCodecs)
            .onLoopback(handler = { mirror })
            .use { app -> app.call(echo, Note("café")) shouldBe Note("café") }
    }
}
