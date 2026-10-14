package io.github.matthewjones372.pelican

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * A request is built once per call on a client's hot path, so the settings past
 * the four every request has are `with` calls rather than a block.
 */
class ClientRequestTest {

    private val get = ClientRequest(Method.GET, "https://orders.internal/orders/1")

    @Test
    fun `a request nobody gave a deadline waits as long as its transport does`() {
        get.timeout.shouldBeNull()
        get.headers.shouldBeEmpty()
        get.body shouldBe ClientRequest.Body.Empty
    }

    @Test
    fun `withTimeout answers a new request and leaves the one it was asked of alone`() {
        val deadline = get.withTimeout(Duration.ofSeconds(2))

        deadline.timeout shouldBe Duration.ofSeconds(2)
        deadline.method shouldBe get.method
        deadline.url shouldBe get.url
        get.timeout.shouldBeNull()
    }

    @Test
    fun `withHeader adds one where it was written, and keeps what was already sent`() {
        val sent = ClientRequest(Method.GET, "https://orders.internal/orders/1", listOf("Accept" to "text/plain"))
            .withHeader("X-Trace", "abc")

        sent.headers shouldContainExactly listOf("Accept" to "text/plain", "X-Trace" to "abc")
        sent.header("x-trace") shouldBe "abc"
    }
}
