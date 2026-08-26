package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class OutputDeclarationTest {

    data class Widget(val id: Long)

    @Test
    fun `a status outside the HTTP range is refused`() {
        shouldThrow<IllegalArgumentException> { json<Widget>(status = 700) }
            .message.orEmpty() shouldContain "run from 100 to 599"

        shouldThrow<IllegalArgumentException> { json<Widget>(status = 99) }
    }

    /**
     * 419 is legal and in nobody's registry, which is the pair Pekko's
     * `StatusCodes.get` throws for. The status is fine; the backend is where
     * that is handled, so a declaration carrying one is allowed here.
     */
    @Test
    fun `a legal but unregistered status is allowed`() {
        json<Widget>(status = 419).status shouldBe 419
        json<Widget>(status = 218).status shouldBe 218
    }

    @Test
    fun `a documentation-only error is held to the same status range`() {
        shouldThrow<IllegalArgumentException> {
            endpoint {
                get("widgets")
                errorResponse(4004, "Not found, twice over")
                json<Widget>()
            }
        }.message.orEmpty() shouldContain "run from 100 to 599"
    }

    /**
     * The responses map keys on the status, so a documentation-only error under
     * a declared success would silently replace it in the document while the
     * server kept answering with the success.
     */
    @Test
    fun `a documentation-only error cannot shadow a declared response`() {
        shouldThrow<IllegalStateException> {
            endpoint {
                get("widgets")
                errorResponse(200, "An error that is also the success")
                json<Widget>()
            }
        }.message.orEmpty() shouldContain "200"
    }

    @Test
    fun `two documentation-only errors cannot share a status`() {
        shouldThrow<IllegalStateException> {
            endpoint {
                get("widgets")
                errorResponse(404, "No widget")
                errorResponse(404, "Still no widget")
                json<Widget>()
            }
        }.message.orEmpty() shouldContain "404"
    }

    @Test
    fun `a payload cannot be declared under a status that carries no body`() {
        shouldThrow<IllegalArgumentException> { json<Widget>(status = 204) }
            .message.orEmpty() shouldContain "cannot carry a body"

        shouldThrow<IllegalArgumentException> { text(status = 304) }
        shouldThrow<IllegalArgumentException> { json<Widget>(status = 100) }
    }

    @Test
    fun `the same statuses are fine on a response with no body`() {
        empty(status = 204).status shouldBe 204
        empty(status = 304).status shouldBe 304
    }

    /**
     * These six are not arbitrary: they are the ones Pekko's response renderer
     * refuses from a `RawHeader`, and it refuses them with a log line rather
     * than an error — so a header declared and then dropped at render time
     * would never be noticed. The other two backends send a second, conflicting
     * copy instead, which is no better.
     */
    @Test
    fun `a header the server owns cannot be declared`() {
        shouldThrow<IllegalArgumentException> { responseHeader<String>("Content-Type") }
            .message.orEmpty() shouldContain "set by the server"

        listOf("content-length", "Transfer-Encoding", "Connection", "Date", "SERVER").forEach {
            shouldThrow<IllegalArgumentException> { responseHeader<String>(it) }
        }
    }

    @Test
    fun `a declared failure and a thrown one are held to it too`() {
        shouldThrow<IllegalArgumentException> { errorJson<Widget>(700, "Nonsense") }
        shouldThrow<IllegalArgumentException> { errorJson<Widget>(204, "Nothing to say") }
        errorJson<Widget>(419, "Legal, unregistered").status shouldBe 419

        shouldThrow<IllegalArgumentException> { ApiException(700, "Nonsense") }
        ApiException(419, "Legal, unregistered").status shouldBe 419
    }

    @Test
    fun `an ordinary header is untouched`() {
        responseHeader<String>("Location").name shouldBe "Location"
        responseHeader<Long>("Retry-After").optional().required shouldBe false
    }
}
