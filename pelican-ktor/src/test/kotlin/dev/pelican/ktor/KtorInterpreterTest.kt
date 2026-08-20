package dev.pelican.ktor

import dev.pelican.Api
import dev.pelican.jackson.JacksonCodecs
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

    private fun HttpResponse.contentType(): String? = headers[HttpHeaders.ContentType]

    // ------------------------------------------------------------- inputs

    @Test
    fun `a path parameter is decoded into its declared type`() = served { client ->
        val res = client.get("/items/1")
        res.status.value shouldBe 200
        res.contentType() shouldBe "application/json"
        res.bodyAsText() shouldBe """{"id":1,"name":"widget"}"""
    }

    @Test
    fun `a path parameter that does not decode is a 400, not a 404`() = served { client ->
        val res = client.get("/items/not-a-number")
        res.status.value shouldBe 400
        res.bodyAsText() shouldContain "Invalid parameter"
    }

    @Test
    fun `an absent optional query parameter gets the declared default`() = served { client ->
        client.get("/items/count").bodyAsText() shouldBe "3/-"
    }

    @Test
    fun `query parameters are decoded, and a refinement is enforced`() = served { client ->
        client.get("/items/count?limit=7&tag=urgent").bodyAsText() shouldBe "7/urgent"

        val res = client.get("/items/count?limit=0")
        res.status.value shouldBe 400
        res.bodyAsText() shouldContain "between 1 and 100"
    }

    @Test
    fun `a missing required header is a 400 naming the header`() = served { client ->
        val res = client.post("/items") { setBody("""{"name":"rope"}""") }
        res.status.value shouldBe 400
        res.bodyAsText() shouldContain "X-Api-Key"
    }

    @Test
    fun `a JSON body is decoded by the configured codec, defaults included`() = served { client ->
        val res = client.post("/items") {
            header("X-Api-Key", "let-me-in")
            setBody("""{"name":"rope"}""")
        }
        res.status.value shouldBe 201
        res.bodyAsText() shouldBe """{"id":7,"name":"rope"}"""
    }

    @Test
    fun `a malformed body is a 400 rather than a 500`() = served { client ->
        val res = client.post("/items") {
            header("X-Api-Key", "let-me-in")
            setBody("{ not json")
        }
        res.status.value shouldBe 400
        res.bodyAsText() shouldContain "Malformed request body"
    }

    @Test
    fun `a raw body is streamed back without being buffered by the framework`() = served { client ->
        val res = client.post("/echo") { setBody("the quick brown fox") }
        res.status.value shouldBe 200
        res.contentType() shouldBe "application/octet-stream"
        res.bodyAsText() shouldBe "the quick brown fox"
    }

    // ------------------------------------------------------------- outputs

    @Test
    fun `an empty output sends the declared status and no body`() = served { client ->
        val res = client.delete("/items/1")
        res.status.value shouldBe 204
        res.bodyAsText() shouldBe ""
    }

    @Test
    fun `ndjson is one document per line`() = served { client ->
        val res = client.get("/items/stream?limit=2")
        res.contentType() shouldBe "application/x-ndjson"
        res.bodyAsText().lines().filter { it.isNotBlank() } shouldBe
            listOf("""{"id":1,"name":"item-1"}""", """{"id":2,"name":"item-2"}""")
    }

    @Test
    fun `sse frames carry the declared event name`() = served { client ->
        val res = client.get("/items/watch?limit=2")
        res.contentType() shouldBe "text/event-stream"
        res.bodyAsText() shouldBe "event: item\ndata: {\"id\":1,\"name\":\"item-1\"}\n\n" +
            "event: item\ndata: {\"id\":2,\"name\":\"item-2\"}\n\n"
    }

    @Test
    fun `a streamed json array is framed by this module`() = served { client ->
        val res = client.get("/items/list?limit=2")
        res.contentType() shouldBe "application/json"
        res.bodyAsText() shouldBe """[{"id":1,"name":"item-1"},{"id":2,"name":"item-2"}]"""
    }

    @Test
    fun `an empty stream still renders an empty json array`() = testApplication {
        application {
            pelican(Api(listOf(listItems streamedNow { _ -> emptyFlow<Item>() }), JacksonCodecs))
        }
        client.get("/items/list").bodyAsText() shouldBe "[]"
    }

    // ------------------------------------------------------------- failures

    @Test
    fun `a declared failure is sent as its own declared type and status`() = served { client ->
        val res = client.get("/items/2")
        res.status.value shouldBe 404
        res.contentType() shouldBe "application/json"
        res.bodyAsText() shouldContain "No item 2"
    }

    @Test
    fun `two failures of the same payload type keep their own statuses`() = served { client ->
        val unauthorised = client.post("/items") {
            header("X-Api-Key", "wrong")
            setBody("""{"name":"rope"}""")
        }
        unauthorised.status.value shouldBe 401

        val missing = client.post("/items") {
            header("X-Api-Key", "let-me-in")
            setBody("""{"name":"nope"}""")
        }
        missing.status.value shouldBe 404
    }

    @Test
    fun `returning another endpoint's failure is a 500, not an undocumented status`() = served { client ->
        client.get("/misdeclared").status.value shouldBe 500
    }

    @Test
    fun `an ApiException becomes the status it names`() = served { client ->
        val res = client.delete("/items/9")
        res.status.value shouldBe 404
        res.bodyAsText() shouldContain "No item 9"
    }

    @Test
    fun `anything else escaping a handler is a 500`() = served { client ->
        val res = client.get("/boom")
        res.status.value shouldBe 500
        res.bodyAsText() shouldContain "Internal server error"
    }

    // ------------------------------------------------------------- routing

    @Test
    fun `a literal segment wins over a capture regardless of declaration order`() = served { client ->
        // /items/stream and /items/{itemId} both match. Ktor scores a constant
        // segment above a parameter, so no sorting is needed here — unlike the
        // other two backends, whose routers try alternatives in order.
        client.get("/items/stream").contentType() shouldBe "application/x-ndjson"
    }

    @Test
    fun `an unknown path is a 404`() = served { client ->
        client.get("/nothing/here").status.value shouldBe 404
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
        client.put("/items/1").status.value shouldBe 404
    }

    @Test
    fun `an api with no endpoints is refused rather than served empty`() {
        val failure = runCatching {
            testApplication {
                application { pelican(Api(emptyList())) }
                startApplication()
            }
        }
        withClue("an empty API should fail at startup, not answer 404 to everything") {
            failure.isFailure shouldBe true
        }
    }
}
