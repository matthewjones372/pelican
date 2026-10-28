package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.InMemoryClientTransport
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.before
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.responseHeader
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test

/** A filter reading `callerAddress` answers the same on Jetty as under the in-memory client. */
class CallerAddressOnJettyTest {

    companion object {
        private val seenFrom = responseHeader<String>("X-Seen-From")

        private val whoAmI = endpoint {
            get("whoami")
            operationId = "whoAmI"
            emits(seenFrom)
            text()
        }

        /** Echoes the address, so the answer says what the filter read. */
        private val echo = before { p -> p.setHeader(seenFrom, p.callerAddress ?: "none") }

        private fun served(trusting: String?): Api = api(listOf(whoAmI handledNow { "hi" })) {
            filter(echo)
            trusting?.let { callerAddress(trustHeader = it) }
        }

        private val direct = served(trusting = null)
        private val proxied = served(trusting = "CloudFront-Viewer-Address")

        private val onJetty = direct.start(port = 0)
        private val behindProxy = proxied.start(port = 0)
        private val asking = Asking(listOf("X-Seen-From"))

        @JvmStatic
        @AfterAll
        fun stopAll() {
            asking.close()
            onJetty.stop()
            behindProxy.stop()
        }
    }

    private fun inMemory(api: Api, headers: List<Pair<String, String>> = emptyList()): String? =
        InMemoryClientTransport(api)
            .send(ClientRequest(Method.GET, "http://in-memory/whoami", headers))
            .toCompletableFuture().join()
            .header("X-Seen-From")

    @Test
    fun `the socket peer, on Jetty and in memory alike`() {
        val served = asking.ask(onJetty.baseUrl, "GET", "/whoami").headers["X-Seen-From"]

        withClue("Jetty and the in-memory client") {
            served shouldBe "127.0.0.1"
            inMemory(direct) shouldBe served
        }
    }

    @Test
    fun `a header nobody said to trust is not the address`() {
        val served = asking.ask(onJetty.baseUrl, "GET", "/whoami", mapOf("X-Forwarded-For" to "203.0.113.7"))
        served.headers["X-Seen-From"] shouldBe "127.0.0.1"
    }

    @Test
    fun `the trusted header's address, without its port, and the peer where it is absent`() {
        val sent = mapOf("CloudFront-Viewer-Address" to "203.0.113.7:46532")

        asking.ask(behindProxy.baseUrl, "GET", "/whoami", sent).headers["X-Seen-From"] shouldBe "203.0.113.7"
        inMemory(proxied, sent.toList()) shouldBe "203.0.113.7"

        asking.ask(behindProxy.baseUrl, "GET", "/whoami").headers["X-Seen-From"] shouldBe "127.0.0.1"
        inMemory(proxied) shouldBe "127.0.0.1"
    }
}
