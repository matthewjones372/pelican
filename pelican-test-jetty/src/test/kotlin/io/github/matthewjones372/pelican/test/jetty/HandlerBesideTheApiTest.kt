package io.github.matthewjones372.pelican.test.jetty

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.jsonBody
import io.kotest.matchers.shouldBe
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.Callback
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

data class Note(val text: String)

private val note = jsonBody<Note>()

private val echo = endpoint(note) {
    post("notes")
    json<Note>()
}

/** Answers with the body and Content-Type it was sent, standing in for the interpreter's body reading. */
private object Mirror : Handler.Abstract() {
    override fun handle(request: Request, response: Response, callback: Callback): Boolean {
        val body = Content.Source.asString(request, Charsets.UTF_8)
        response.headers.put(HttpHeader.CONTENT_TYPE, request.headers.get(HttpHeader.CONTENT_TYPE))
        response.write(true, ByteBuffer.wrap(body.toByteArray(Charsets.UTF_8)), callback)
        return true
    }
}

class HandlerBesideTheApiTest {

    @Test
    fun `a handler the caller builds receives the body the client sent`() {
        api(endpoints = listOf(echo handledNow { it }), codecs = JacksonCodecs)
            .inMemory(handler = { Mirror })
            .use { app -> app.call(echo, Note("café")) shouldBe Note("café") }
    }
}
