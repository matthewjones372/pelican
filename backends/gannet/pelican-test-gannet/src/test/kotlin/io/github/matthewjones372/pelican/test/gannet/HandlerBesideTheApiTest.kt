package io.github.matthewjones372.pelican.test.gannet

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.gannet.GannetHandler
import io.github.matthewjones372.pelican.gannet.RequiresGannet
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
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
