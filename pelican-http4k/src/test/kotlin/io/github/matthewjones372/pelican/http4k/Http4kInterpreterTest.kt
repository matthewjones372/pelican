package io.github.matthewjones372.pelican.http4k

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
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
        res.status.code shouldBe 200
        res.header("Content-Type") shouldBe "application/json"
        res.bodyString() shouldBe """{"id":1,"name":"widget"}"""
    }

    @Test
    fun `a path parameter that does not decode is a 400, not a 404`() {
        val res = get("/items/not-a-number")
        res.status.code shouldBe 400
        res.bodyString() shouldContain "Invalid parameter"
    }

    @Test
    fun `an absent optional query parameter gets the declared default`() {
        get("/items/count").bodyString() shouldBe "3/-"
    }

    @Test
    fun `query parameters are decoded, and a refinement is enforced`() {
        get("/items/count?limit=7&tag=urgent").bodyString() shouldBe "7/urgent"

        val res = get("/items/count?limit=0")
        res.status.code shouldBe 400
        res.bodyString() shouldContain "between 1 and 100"
    }

    @Test
    fun `a missing required header is a 400 naming the header`() {
        val res = handler(Request(Method.POST, "/items").body("""{"name":"rope"}"""))
        res.status.code shouldBe 400
        res.bodyString() shouldContain "X-Api-Key"
    }

    @Test
    fun `a JSON body is decoded by the configured codec, defaults included`() {
        val res = handler(
            Request(Method.POST, "/items")
                .header("X-Api-Key", "let-me-in")
                .body("""{"name":"rope"}"""),
        )
        res.status.code shouldBe 201
        res.bodyString() shouldBe """{"id":7,"name":"rope"}"""
    }

    @Test
    fun `a malformed body is a 400 rather than a 500`() {
        val res = handler(
            Request(Method.POST, "/items")
                .header("X-Api-Key", "let-me-in")
                .body("{ not json"),
        )
        res.status.code shouldBe 400
        res.bodyString() shouldContain "Malformed request body"
    }

    @Test
    fun `a raw body is streamed back without being buffered by the framework`() {
        val res = handler(Request(Method.POST, "/echo").body("the quick brown fox"))
        res.status.code shouldBe 200
        res.header("Content-Type") shouldBe "application/octet-stream"
        res.bodyString() shouldBe "the quick brown fox"
    }

    // ------------------------------------------------------------- outputs

    @Test
    fun `an empty output sends the declared status and no body`() {
        val res = handler(Request(Method.DELETE, "/items/1"))
        res.status.code shouldBe 204
        res.bodyString() shouldBe ""
    }

    @Test
    fun `ndjson is one document per line`() {
        val res = get("/items/stream?limit=2")
        res.header("Content-Type") shouldBe "application/x-ndjson"
        res.bodyString().lines().filter { it.isNotBlank() } shouldBe
            listOf("""{"id":1,"name":"item-1"}""", """{"id":2,"name":"item-2"}""")
    }

    @Test
    fun `sse frames carry the declared event name`() {
        val res = get("/items/watch?limit=2")
        res.header("Content-Type") shouldBe "text/event-stream"
        res.bodyString() shouldBe "event: item\ndata: {\"id\":1,\"name\":\"item-1\"}\n\n" +
            "event: item\ndata: {\"id\":2,\"name\":\"item-2\"}\n\n"
    }

    @Test
    fun `a streamed json array is framed by this module`() {
        val res = get("/items/list?limit=2")
        res.header("Content-Type") shouldBe "application/json"
        res.bodyString() shouldBe """[{"id":1,"name":"item-1"},{"id":2,"name":"item-2"}]"""
    }

    @Test
    fun `an empty stream still renders an empty json array`() {
        val empty = api(
            listOf(listItems streamedNow { _ -> emptySequence<Item>() }),
            JacksonCodecs,
        )
            .toHttpHandler()
        empty(Request(Method.GET, "/items/list")).bodyString() shouldBe "[]"
    }

    // ------------------------------------------------------------- failures

    @Test
    fun `a declared failure is sent as its own declared type and status`() {
        val res = get("/items/2")
        res.status.code shouldBe 404
        res.header("Content-Type") shouldBe "application/json"
        res.bodyString() shouldContain """No item 2"""
    }

    @Test
    fun `two failures of the same payload type keep their own statuses`() {
        val unauthorised = handler(
            Request(Method.POST, "/items").header("X-Api-Key", "wrong").body("""{"name":"rope"}"""),
        )
        unauthorised.status.code shouldBe 401

        val missing = handler(
            Request(Method.POST, "/items").header("X-Api-Key", "let-me-in").body("""{"name":"nope"}"""),
        )
        missing.status.code shouldBe 404
    }

    @Test
    fun `a byte stream can fail before its first byte`() {
        val ok = get("/blobs/1")
        ok.status.code shouldBe 200
        ok.bodyString() shouldBe "blobby"

        val missing = get("/blobs/9")
        missing.status.code shouldBe 404
        missing.bodyString() shouldContain "No blob 9"
    }

    @Test
    fun `returning another endpoint's failure is a 500, not an undocumented status`() {
        val res = get("/misdeclared")
        res.status.code shouldBe 500
    }

    @Test
    fun `an ApiException becomes the status it names`() {
        val res = handler(Request(Method.DELETE, "/items/9"))
        res.status.code shouldBe 404
        res.bodyString() shouldContain "No item 9"
    }

    @Test
    fun `anything else escaping a handler is a 500`() {
        val res = get("/boom")
        res.status.code shouldBe 500
        res.bodyString() shouldContain "Internal server error"
    }

    // ------------------------------------------------------------- routing

    @Test
    fun `a more specific path wins over a capture regardless of declaration order`() {
        // /items/stream and /items/{itemId} both match; the literal one is tried
        // first, so this is a stream rather than "no item 'stream'".
        get("/items/stream").header("Content-Type") shouldBe "application/x-ndjson"
    }

    @Test
    fun `an unknown path is a 404`() {
        get("/nothing/here").status.code shouldBe 404
    }

    @Test
    fun `a known path with the wrong method is a 405`() {
        handler(Request(Method.PUT, "/items/1")).status.code shouldBe 405
    }
}
