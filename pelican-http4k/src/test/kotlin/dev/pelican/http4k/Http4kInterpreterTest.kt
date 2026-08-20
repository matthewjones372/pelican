package dev.pelican.http4k

import dev.pelican.Api
import dev.pelican.jackson.JacksonCodecs
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The interpreter, exercised as a function.
 *
 * There is no in-memory transport to set up here and no server to bind: an
 * http4k handler *is* `(Request) -> Response`, so an in-memory test and a
 * bound-server test differ only in what sits between the two. That is the
 * property this backend gets for free and `pelican-test`'s `InMemoryTransport`
 * has to build for Pekko.
 */
class Http4kInterpreterTest {

    private val handler: HttpHandler = testApi().toHttpHandler()

    private fun get(target: String): Response = handler(Request(Method.GET, target))

    // ------------------------------------------------------------- inputs

    @Test
    fun `a path parameter is decoded into its declared type`() {
        val res = get("/items/1")
        assertEquals(200, res.status.code)
        assertEquals("application/json", res.header("Content-Type"))
        assertEquals("""{"id":1,"name":"widget"}""", res.bodyString())
    }

    @Test
    fun `a path parameter that does not decode is a 400, not a 404`() {
        val res = get("/items/not-a-number")
        assertEquals(400, res.status.code)
        assertTrue("Invalid parameter" in res.bodyString(), res.bodyString())
    }

    @Test
    fun `an absent optional query parameter gets the declared default`() {
        assertEquals("3/-", get("/items/count").bodyString())
    }

    @Test
    fun `query parameters are decoded, and a refinement is enforced`() {
        assertEquals("7/urgent", get("/items/count?limit=7&tag=urgent").bodyString())

        val res = get("/items/count?limit=0")
        assertEquals(400, res.status.code)
        assertTrue("between 1 and 100" in res.bodyString(), res.bodyString())
    }

    @Test
    fun `a missing required header is a 400 naming the header`() {
        val res = handler(Request(Method.POST, "/items").body("""{"name":"rope"}"""))
        assertEquals(400, res.status.code)
        assertTrue("X-Api-Key" in res.bodyString(), res.bodyString())
    }

    @Test
    fun `a JSON body is decoded by the configured codec, defaults included`() {
        val res = handler(
            Request(Method.POST, "/items")
                .header("X-Api-Key", "let-me-in")
                .body("""{"name":"rope"}"""),
        )
        assertEquals(201, res.status.code)
        assertEquals("""{"id":7,"name":"rope"}""", res.bodyString())
    }

    @Test
    fun `a malformed body is a 400 rather than a 500`() {
        val res = handler(
            Request(Method.POST, "/items")
                .header("X-Api-Key", "let-me-in")
                .body("{ not json"),
        )
        assertEquals(400, res.status.code)
        assertTrue("Malformed request body" in res.bodyString(), res.bodyString())
    }

    @Test
    fun `a raw body is streamed back without being buffered by the framework`() {
        val res = handler(Request(Method.POST, "/echo").body("the quick brown fox"))
        assertEquals(200, res.status.code)
        assertEquals("application/octet-stream", res.header("Content-Type"))
        assertEquals("the quick brown fox", res.bodyString())
    }

    // ------------------------------------------------------------- outputs

    @Test
    fun `an empty output sends the declared status and no body`() {
        val res = handler(Request(Method.DELETE, "/items/1"))
        assertEquals(204, res.status.code)
        assertEquals("", res.bodyString())
    }

    @Test
    fun `ndjson is one document per line`() {
        val res = get("/items/stream?limit=2")
        assertEquals("application/x-ndjson", res.header("Content-Type"))
        assertEquals(
            listOf("""{"id":1,"name":"item-1"}""", """{"id":2,"name":"item-2"}"""),
            res.bodyString().lines().filter { it.isNotBlank() },
        )
    }

    @Test
    fun `sse frames carry the declared event name`() {
        val res = get("/items/watch?limit=2")
        assertEquals("text/event-stream", res.header("Content-Type"))
        assertEquals(
            "event: item\ndata: {\"id\":1,\"name\":\"item-1\"}\n\n" +
                "event: item\ndata: {\"id\":2,\"name\":\"item-2\"}\n\n",
            res.bodyString(),
        )
    }

    @Test
    fun `a streamed json array is framed by this module`() {
        val res = get("/items/list?limit=2")
        assertEquals("application/json", res.header("Content-Type"))
        assertEquals(
            """[{"id":1,"name":"item-1"},{"id":2,"name":"item-2"}]""",
            res.bodyString(),
        )
    }

    @Test
    fun `an empty stream still renders an empty json array`() {
        val empty = Api(listOf(listItems streamedNow { _ -> emptySequence<Item>() }), JacksonCodecs)
            .toHttpHandler()
        assertEquals("[]", empty(Request(Method.GET, "/items/list")).bodyString())
    }

    // ------------------------------------------------------------- failures

    @Test
    fun `a declared failure is sent as its own declared type and status`() {
        val res = get("/items/2")
        assertEquals(404, res.status.code)
        assertEquals("application/json", res.header("Content-Type"))
        assertTrue("""No item 2""" in res.bodyString(), res.bodyString())
    }

    @Test
    fun `two failures of the same payload type keep their own statuses`() {
        val unauthorised = handler(
            Request(Method.POST, "/items").header("X-Api-Key", "wrong").body("""{"name":"rope"}"""),
        )
        assertEquals(401, unauthorised.status.code)

        val missing = handler(
            Request(Method.POST, "/items").header("X-Api-Key", "let-me-in").body("""{"name":"nope"}"""),
        )
        assertEquals(404, missing.status.code)
    }

    @Test
    fun `returning another endpoint's failure is a 500, not an undocumented status`() {
        val res = get("/misdeclared")
        assertEquals(500, res.status.code)
    }

    @Test
    fun `an ApiException becomes the status it names`() {
        val res = handler(Request(Method.DELETE, "/items/9"))
        assertEquals(404, res.status.code)
        assertTrue("No item 9" in res.bodyString(), res.bodyString())
    }

    @Test
    fun `anything else escaping a handler is a 500`() {
        val res = get("/boom")
        assertEquals(500, res.status.code)
        assertTrue("Internal server error" in res.bodyString(), res.bodyString())
    }

    // ------------------------------------------------------------- routing

    @Test
    fun `a more specific path wins over a capture regardless of declaration order`() {
        // /items/stream and /items/{itemId} both match; the literal one is tried
        // first, so this is a stream rather than "no item 'stream'".
        assertEquals("application/x-ndjson", get("/items/stream").header("Content-Type"))
    }

    @Test
    fun `an unknown path is a 404`() {
        assertEquals(404, get("/nothing/here").status.code)
    }

    @Test
    fun `a known path with the wrong method is a 405`() {
        assertEquals(405, handler(Request(Method.PUT, "/items/1")).status.code)
    }
}
