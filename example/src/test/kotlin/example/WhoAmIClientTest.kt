package example

import example.generated.whoami.ApiCallFailed
import example.generated.whoami.WhoAmIClient
import example.whoami.ADA_TOKEN
import example.whoami.whoAmIApi
import io.github.matthewjones372.pelican.client.jetty.JettyHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.start
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test

/** The generated client against a running server: the 401 the document declares is the one it is refused with. */
class WhoAmIClientTest {

    companion object {
        private val server = whoAmIApi().start(port = 0)
        private val http = JettyHttpTransport()

        @JvmStatic
        @AfterAll
        fun stop() {
            http.close()
            server.stop()
        }
    }

    private fun client(token: String?) = WhoAmIClient(server.baseUrl, JacksonCodecs, http) {
        token?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
    }

    @Test
    fun `a caller the token names is answered`() {
        client(ADA_TOKEN).whoAmI() shouldBe "ada"
    }

    @Test
    fun `a call naming nobody is refused with a 401`() {
        shouldThrow<ApiCallFailed> { client(token = null).whoAmI() }.status shouldBe 401
    }

    @Test
    fun `a token that does not verify is refused with a 401 that says so, and does not repeat it`() {
        val refused = shouldThrow<ApiCallFailed> { client("mallory-token").whoAmI() }

        refused.status shouldBe 401
        refused.message shouldContain "not one this service issued"
        refused.message shouldNotContain "mallory-token"
    }
}
