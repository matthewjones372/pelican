package example.backends

import dev.pelican.Api
import dev.pelican.In2
import dev.pelican.ServerEndpoint
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.ok
import dev.pelican.test.ResponseSpec
import dev.pelican.test.apiClient
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import dev.pelican.http4k.handledOneOf as handledOneOfOnHttp4k
import dev.pelican.http4k.start as startOnHttp4k
import dev.pelican.ktor.handledOneOf as handledOneOfOnKtor
import dev.pelican.ktor.start as startOnKtor
import dev.pelican.pekko.handledOneOf as handledOneOfOnPekko
import dev.pelican.pekko.start as startOnPekko

/**
 * The one thing a bare `ok(...)` cannot say, asked of all three interpreters.
 *
 * `remember` answers `newlyLearned or alreadyKnown`, and the 201 declares a
 * `Location` it always sends. Naming that response is what supplies the header
 * — `newlyLearned(greeting, greetingAt of "...")` — and `ok(value)` names no
 * response, so it has nowhere to put one. Left alone it would answer 201 with
 * no `Location` while the published document promises one, which is the one
 * kind of wrong answer a caller cannot see is wrong.
 *
 * The handler below is therefore deliberately incorrect; `Greetings.kt` has the
 * right one. What is under test is that all three backends refuse it out loud
 * rather than each deciding for itself, which they do because the decision is
 * core's — `successNamedBy` — and not theirs.
 */
class BareOkTest {

    /** A 500 says the response was refused; a 201 says it went out unpromised. */
    private fun ResponseSpec.shouldBeRefused() {
        withClue("the response body was: $body") { status shouldBe 500 }
        withClue("a 201 without its Location is exactly what this must not send") {
            header("Location").shouldBeNull()
        }
    }

    private fun brokenApi(route: ServerEndpoint): Api = greetingsApi(listOf(route), covers = emptyList())

    private fun asking(baseUrl: String) =
        apiClient(baseUrl, JacksonCodecs).use { it.response(remember, In2("ada", Note("hello"))) }

    @Test
    fun `pekko refuses a bare ok where the response it means promises a header`() {
        val server = brokenApi(
            remember handledOneOfOnPekko { (_, note) -> ok(Greeting(note.text, language = "en")) },
        ).startOnPekko(port = 0, systemName = "greetings-bare-ok")

        try {
            asking(server.baseUrl).shouldBeRefused()
        } finally {
            server.stop().toCompletableFuture().join()
        }
    }

    @Test
    fun `http4k refuses it too`() {
        val server = brokenApi(
            remember handledOneOfOnHttp4k { (_, note) -> ok(Greeting(note.text, language = "en")) },
        ).startOnHttp4k(port = 0)

        try {
            asking(server.baseUrl).shouldBeRefused()
        } finally {
            server.stop()
        }
    }

    @Test
    fun `and so does ktor`() {
        val server = brokenApi(
            remember handledOneOfOnKtor { (_, note) -> ok(Greeting(note.text, language = "en")) },
        ).startOnKtor(port = 0)

        try {
            asking(server.baseUrl).shouldBeRefused()
        } finally {
            server.stop()
        }
    }
}
