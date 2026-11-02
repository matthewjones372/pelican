package example.backends

import dev.surefold.pelican.Api
import dev.surefold.pelican.In2
import dev.surefold.pelican.ServerEndpoint
import dev.surefold.pelican.gannet.RequiresGannet
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.ok
import dev.surefold.pelican.test.ResponseSpec
import dev.surefold.pelican.test.apiClient
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import dev.surefold.pelican.gannet.handledOneOf as handledOneOfOnGannet
import dev.surefold.pelican.gannet.start as startOnGannet
import dev.surefold.pelican.jetty.handledOneOf as handledOneOfOnJetty
import dev.surefold.pelican.jetty.start as startOnJetty
import dev.surefold.pelican.pekko.handledOneOf as handledOneOfOnPekko
import dev.surefold.pelican.pekko.start as startOnPekko

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

    @Test
    fun `and so does jetty`() {
        val server = brokenApi(
            remember handledOneOfOnJetty { (_, note) -> ok(Greeting(note.text, language = "en")) },
        ).startOnJetty(port = 0)

        try {
            asking(server.baseUrl).shouldBeRefused()
        } finally {
            server.stop()
        }
    }

    @Test
    @RequiresGannet
    fun `and so does gannet`() {
        val server = brokenApi(
            remember handledOneOfOnGannet { (_, note) -> ok(Greeting(note.text, language = "en")) },
        ).startOnGannet(port = 0)

        try {
            asking(server.baseUrl).shouldBeRefused()
        } finally {
            server.stop()
        }
    }
}
