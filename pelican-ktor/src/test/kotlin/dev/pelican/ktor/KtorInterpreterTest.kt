package dev.pelican.ktor

import dev.pelican.Api
import dev.pelican.jackson.JacksonCodecs
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.emptyFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The interpreter, exercised through Ktor's own test engine.
 *
 * `testApplication` runs the real routing tree, the real plugins and the real
 * response pipeline with no socket in between, so what is checked here is the
 * interpreter rather than the network: decoding, statuses, framing and the
 * routing rules Ktor applies to the templates it is given.
 */
class KtorInterpreterTest {

    private fun api() = testApi()

    /** Runs [block] against the test application, so every test reads the same. */
    private fun served(block: suspend (io.ktor.client.HttpClient) -> Unit) = testApplication {
        application { pelican(api()) }
        block(client)
    }

    private suspend fun HttpResponse.contentType(): String? = headers[HttpHeaders.ContentType]

    // ------------------------------------------------------------- inputs

    @Test
    fun `a path parameter is decoded into its declared type`() = served { client ->
        val res = client.get("/items/1")
        assertEquals(200, res.status.value)
        assertEquals("application/json", res.contentType())
        assertEquals("""{"id":1,"name":"widget"}""", res.bodyAsText())
    }

    @Test
    fun `a path parameter that does not decode is a 400, not a 404`() = served { client ->
        val res = client.get("/items/not-a-number")
        assertEquals(400, res.status.value)
        assertTrue("Invalid parameter" in res.bodyAsText(), res.bodyAsText())
    }

    @Test
    fun `an absent optional query parameter gets the declared default`() = served { client ->
        assertEquals("3/-", client.get("/items/count").bodyAsText())
    }

    @Test
    fun `query parameters are decoded, and a refinement is enforced`() = served { client ->
        assertEquals("7/urgent", client.get("/items/count?limit=7&tag=urgent").bodyAsText())

        val res = client.get("/items/count?limit=0")
        assertEquals(400, res.status.value)
        assertTrue("between 1 and 100" in res.bodyAsText(), res.bodyAsText())
    }

    @Test
    fun `a missing required header is a 400 naming the header`() = served { client ->
        val res = client.post("/items") { setBody("""{"name":"rope"}""") }
        assertEquals(400, res.status.value)
        assertTrue("X-Api-Key" in res.bodyAsText(), res.bodyAsText())
    }

    @Test
    fun `a JSON body is decoded by the configured codec, defaults included`() = served { client ->
        val res = client.post("/items") {
            header("X-Api-Key", "let-me-in")
            setBody("""{"name":"rope"}""")
        }
        assertEquals(201, res.status.value)
        assertEquals("""{"id":7,"name":"rope"}""", res.bodyAsText())
    }

    @Test
    fun `a malformed body is a 400 rather than a 500`() = served { client ->
        val res = client.post("/items") {
            header("X-Api-Key", "let-me-in")
            setBody("{ not json")
        }
        assertEquals(400, res.status.value)
        assertTrue("Malformed request body" in res.bodyAsText(), res.bodyAsText())
    }

    @Test
    fun `a raw body is streamed back without being buffered by the framework`() = served { client ->
        val res = client.post("/echo") { setBody("the quick brown fox") }
        assertEquals(200, res.status.value)
        assertEquals("application/octet-stream", res.contentType())
        assertEquals("the quick brown fox", res.bodyAsText())
    }

    // ------------------------------------------------------------- outputs

    @Test
    fun `an empty output sends the declared status and no body`() = served { client ->
        val res = client.delete("/items/1")
        assertEquals(204, res.status.value)
        assertEquals("", res.bodyAsText())
    }

    @Test
    fun `ndjson is one document per line`() = served { client ->
        val res = client.get("/items/stream?limit=2")
        assertEquals("application/x-ndjson", res.contentType())
        assertEquals(
            listOf("""{"id":1,"name":"item-1"}""", """{"id":2,"name":"item-2"}"""),
            res.bodyAsText().lines().filter { it.isNotBlank() },
        )
    }

    @Test
    fun `sse frames carry the declared event name`() = served { client ->
        val res = client.get("/items/watch?limit=2")
        assertEquals("text/event-stream", res.contentType())
        assertEquals(
            "event: item\ndata: {\"id\":1,\"name\":\"item-1\"}\n\n" +
                "event: item\ndata: {\"id\":2,\"name\":\"item-2\"}\n\n",
            res.bodyAsText(),
        )
    }

    @Test
    fun `a streamed json array is framed by this module`() = served { client ->
        val res = client.get("/items/list?limit=2")
        assertEquals("application/json", res.contentType())
        assertEquals("""[{"id":1,"name":"item-1"},{"id":2,"name":"item-2"}]""", res.bodyAsText())
    }

    @Test
    fun `an empty stream still renders an empty json array`() = testApplication {
        application {
            pelican(Api(listOf(listItems streamedNow { _ -> emptyFlow<Item>() }), JacksonCodecs))
        }
        assertEquals("[]", client.get("/items/list").bodyAsText())
    }

    // ------------------------------------------------------------- failures

    @Test
    fun `a declared failure is sent as its own declared type and status`() = served { client ->
        val res = client.get("/items/2")
        assertEquals(404, res.status.value)
        assertEquals("application/json", res.contentType())
        assertTrue("No item 2" in res.bodyAsText(), res.bodyAsText())
    }

    @Test
    fun `two failures of the same payload type keep their own statuses`() = served { client ->
        val unauthorised = client.post("/items") {
            header("X-Api-Key", "wrong")
            setBody("""{"name":"rope"}""")
        }
        assertEquals(401, unauthorised.status.value)

        val missing = client.post("/items") {
            header("X-Api-Key", "let-me-in")
            setBody("""{"name":"nope"}""")
        }
        assertEquals(404, missing.status.value)
    }

    @Test
    fun `returning another endpoint's failure is a 500, not an undocumented status`() = served { client ->
        assertEquals(500, client.get("/misdeclared").status.value)
    }

    @Test
    fun `an ApiException becomes the status it names`() = served { client ->
        val res = client.delete("/items/9")
        assertEquals(404, res.status.value)
        assertTrue("No item 9" in res.bodyAsText(), res.bodyAsText())
    }

    @Test
    fun `anything else escaping a handler is a 500`() = served { client ->
        val res = client.get("/boom")
        assertEquals(500, res.status.value)
        assertTrue("Internal server error" in res.bodyAsText(), res.bodyAsText())
    }

    // ------------------------------------------------------------- routing

    @Test
    fun `a literal segment wins over a capture regardless of declaration order`() = served { client ->
        // /items/stream and /items/{itemId} both match. Ktor scores a constant
        // segment above a parameter, so no sorting is needed here — unlike the
        // other two backends, whose routers try alternatives in order.
        assertEquals("application/x-ndjson", client.get("/items/stream").contentType())
    }

    @Test
    fun `an unknown path is a 404`() = served { client ->
        assertEquals(404, client.get("/nothing/here").status.value)
    }

    /**
     * Ktor's own answer, not this module's choice: its router does not
     * distinguish "no such path" from "not that method", and answers 404 to
     * both. http4k answers 405 to the same request and Pekko answers either
     * depending on what else is declared, which is why nothing here asserts one
     * number across backends.
     */
    @Test
    fun `a known path with the wrong method is a 404 on this backend`() = served { client ->
        assertEquals(404, client.put("/items/1").status.value)
    }

    @Test
    fun `an api with no endpoints is refused rather than served empty`() {
        val failure = runCatching {
            testApplication {
                application { pelican(Api(emptyList())) }
                startApplication()
            }
        }
        assertTrue(failure.isFailure, "an empty API should fail at startup, not answer 404 to everything")
    }
}
