package example

import example.backends.OnJetty
import example.generated.tipping.Tip
import example.generated.tipping.TippingClient
import io.github.matthewjones372.pelican.ClientTransport
import io.github.matthewjones372.pelican.client.jetty.JettyHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.retryPolicy
import io.github.matthewjones372.pelican.retrying
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/** The generated client's keys, against a running server: one per call, and the same one when a call is retried. */
class TippingClientTest {

    companion object {
        private val server = OnJetty.start(port = 0)
        private val http = JettyHttpTransport()

        @JvmStatic
        @AfterAll
        fun stop() {
            http.close()
            server.stop()
        }
    }

    @Test
    fun `a retry after the answer was lost gets the receipt the server already gave, not a second tip`() {
        val sent = AtomicInteger()
        val keys = mutableListOf<String?>()
        // The server answers the first send; the answer is dropped on the way back, as a reset connection drops it.
        val dropsFirstAnswer = ClientTransport { request ->
            keys += request.header("Idempotency-Key")
            http.send(request).thenCompose { response ->
                if (sent.incrementAndGet() == 1) {
                    response.body.close()
                    CompletableFuture.failedFuture(IOException("connection reset after the server answered"))
                } else {
                    CompletableFuture.completedFuture(response)
                }
            }
        }
        val client = TippingClient(
            server.baseUrl,
            JacksonCodecs,
            dropsFirstAnswer.retrying(retryPolicy { jitter = 0.0 }) { _, task -> task.run() },
        )

        val receipt = client.leaveTip(Tip(7))
        val next = client.leaveTip(Tip(7))

        keys.size shouldBe 3
        keys[1] shouldBe keys[0]
        keys[2] shouldNotBe keys[0]
        // Numbered by the handler: the retry was answered from the first run, so the next call is the next number.
        next.number shouldBe receipt.number + 1
    }

    @Test
    fun `a key the caller names is the key that is sent`() {
        val client = TippingClient(server.baseUrl, JacksonCodecs, http)

        client.leaveTip(Tip(3), idempotencyKey = "tip-42") shouldBe client.leaveTip(Tip(3), idempotencyKey = "tip-42")
    }
}
