package io.github.matthewjones372.pelican.test

import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/**
 * What an assertion on a [ResponseSpec] should say when it fails.
 *
 * `assertEquals(404, res.status)` reports "expected: <404> but was: <500>" and
 * leaves you to go and find the body that would explain why. These print it,
 * because a wrong status is nearly always explained by the payload that came
 * with it — so the body appearing in the message is the behaviour under test,
 * not a detail of it.
 */
class ResponseAssertionsTest {

    private val notFound = ResponseSpec(
        status = 404,
        headers = listOf("Content-Type" to "application/json; charset=utf-8"),
        body = """{"status":404,"error":"No bookmark with that id"}""",
    )

    private val created = ResponseSpec(201, listOf("Location" to "/bookmarks/1"), body = "")

    /** The message these assertions are really about — the reason they exist. */
    private inline fun messageOfFailing(block: () -> Unit): String =
        shouldThrow<AssertionError>(block).message.shouldNotBeNull()

    // ------------------------------------------------------------------ status

    @Test
    fun `shouldHaveStatus returns the response, so assertions chain`() {
        (notFound shouldHaveStatus 404) shouldBeSameInstanceAs notFound
    }

    @Test
    fun `shouldHaveStatus prints the body, which is what explains the wrong status`() {
        val message = messageOfFailing { notFound shouldHaveStatus 200 }

        message shouldContain "Expected status 200 but was 404"
        message shouldContain "No bookmark with that id"
    }

    @Test
    fun `shouldHaveStatus truncates a long body rather than filling the console`() {
        val chatty = ResponseSpec(500, emptyList(), body = "x".repeat(5_000))

        messageOfFailing { chatty shouldHaveStatus 200 }.length shouldBe
            "Expected status 200 but was 500. Body:\n".length + 500
    }

    // ------------------------------------------------------------- successful

    @Test
    fun `shouldBeSuccessful accepts any 2xx`() {
        created.shouldBeSuccessful() shouldBeSameInstanceAs created
    }

    @Test
    fun `shouldBeSuccessful on a failure says what came back`() {
        val message = messageOfFailing { notFound.shouldBeSuccessful() }

        message shouldContain "Expected a 2xx but was 404"
        message shouldContain "No bookmark with that id"
    }

    // ------------------------------------------------------------ content type

    @Test
    fun `shouldHaveContentType compares the media type without its parameters`() {
        // The header says "; charset=utf-8"; a test asserting on the media type
        // should not have to know whether the server appended one.
        notFound shouldHaveContentType "application/json"
    }

    @Test
    fun `shouldHaveContentType says what the header actually was`() {
        val message = messageOfFailing { notFound shouldHaveContentType "text/html" }

        message shouldContain "'text/html'"
        message shouldContain "'application/json'"
    }

    @Test
    fun `shouldHaveContentType on a response with no such header`() {
        messageOfFailing { created shouldHaveContentType "application/json" } shouldContain "was 'null'"
    }

    // ------------------------------------------------------------------ header

    @Test
    fun `shouldHaveHeader matches the name case-insensitively, as HTTP does`() {
        created.shouldHaveHeader("location", "/bookmarks/1")
    }

    @Test
    fun `shouldHaveHeader names the header, its expected value and what was there`() {
        val message = messageOfFailing { created.shouldHaveHeader("Location", "/bookmarks/2") }

        message shouldContain "Location: /bookmarks/2"
        message shouldContain "/bookmarks/1"
    }

    @Test
    fun `shouldHaveHeader on a missing header reports it as absent`() {
        messageOfFailing { created.shouldHaveHeader("ETag", "abc") } shouldContain "was 'null'"
    }

    // -------------------------------------------------------------------- body

    @Test
    fun `shouldHaveNoBody passes on the empty body a 201 or 204 carries`() {
        created.shouldHaveNoBody() shouldBeSameInstanceAs created
    }

    @Test
    fun `shouldHaveNoBody counts what it got, so a stray newline is visible`() {
        val message = messageOfFailing { notFound.shouldHaveNoBody() }

        message shouldContain "got ${notFound.body.length} chars"
        message shouldContain "No bookmark with that id"
    }

    // -------------------------------------------------------------- api errors

    private val client = ApiClient(
        transport = object : Transport {
            override fun send(request: RequestSpec): ResponseSpec =
                error("no request is sent; these assert on a response already in hand")
        },
        codecs = JacksonCodecs,
    )

    @Test
    fun `errorBody decodes the framework's own error shape`() {
        client.errorBody(notFound) shouldBe ApiError(404, "No bookmark with that id")
    }

    @Test
    fun `shouldBeApiError checks the status, the content type and the payload at once`() {
        client.shouldBeApiError(notFound, 404, "No bookmark with that id") shouldBe
            ApiError(404, "No bookmark with that id")
    }

    @Test
    fun `shouldBeApiError fails on the status before it tries to decode a body`() {
        // A 500 from a crashed handler is rarely the JSON this would decode;
        // reporting a decoding failure there would hide the real answer.
        val html = ResponseSpec(500, listOf("Content-Type" to "text/html"), body = "<h1>Oops</h1>")

        messageOfFailing {
            client.shouldBeApiError(html, 404, "No bookmark with that id")
        } shouldContain "Expected status 404 but was 500"
    }

    @Test
    fun `shouldBeApiError prints both sides when the message differs`() {
        val message = messageOfFailing { client.shouldBeApiError(notFound, 404, "Not found") }

        message shouldContain """ApiError(status=404, error="Not found")"""
        message shouldContain "No bookmark with that id"
    }
}
