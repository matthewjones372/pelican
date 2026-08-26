package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.In2
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.apiClient
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import io.github.matthewjones372.pelican.pekko.handledOneOf as handledOneOfOnPekko
import io.github.matthewjones372.pelican.pekko.start as startOnPekko

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
            server.stop()
        }
    }
}
