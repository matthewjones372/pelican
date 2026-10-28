package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/** `Params.callerAddress` under the in-memory client: the peer it stands in for, or a header the Api trusts. */
class CallerAddressTest {

    private val whoAmI = endpoint {
        get("whoami")
        text()
    }

    private val answering = ServerEndpoint(whoAmI) { p -> CompletableFuture.completedStage(p.callerAddress as Any?) }

    private fun served(configure: ApiBuilder.() -> Unit = {}): Api = api(listOf(answering), NoCodecs, configure)

    private fun InMemoryClientTransport.ask(vararg headers: Pair<String, String>): String =
        send(ClientRequest(Method.GET, "http://test/whoami", headers.toList())).toCompletableFuture().join().text()

    @Test
    fun `the in-memory client calls from loopback unless told otherwise`() {
        InMemoryClientTransport(served()).ask() shouldBe "127.0.0.1"
        InMemoryClientTransport(served(), callerAddress = "198.51.100.4").ask() shouldBe "198.51.100.4"
    }

    @Test
    fun `a forwarding header is ignored until the Api names it`() {
        InMemoryClientTransport(served()).ask("X-Forwarded-For" to "203.0.113.7") shouldBe "127.0.0.1"
    }

    @Test
    fun `a trusted header gives its first entry, without brackets or a port`() {
        val transport = InMemoryClientTransport(served { callerAddress(trustHeader = "X-Forwarded-For") })

        transport.ask("X-Forwarded-For" to "203.0.113.7, 10.0.0.1") shouldBe "203.0.113.7"
        transport.ask("x-forwarded-for" to "203.0.113.7:46532") shouldBe "203.0.113.7"
        transport.ask("X-Forwarded-For" to "[2001:db8::1]:443") shouldBe "2001:db8::1"
        transport.ask("X-Forwarded-For" to "2001:db8::1") shouldBe "2001:db8::1"
    }

    @Test
    fun `without the trusted header it is the peer`() {
        InMemoryClientTransport(served { callerAddress(trustHeader = "CloudFront-Viewer-Address") }).ask() shouldBe
            "127.0.0.1"
    }

    @Test
    fun `a hand-built Params has no address`() {
        Params(emptyMap(), underlying = null).callerAddress.shouldBeNull()
    }

    @Test
    fun `a blank header name is refused`() {
        shouldThrow<IllegalArgumentException> { served { callerAddress(trustHeader = " ") } }
    }
}
