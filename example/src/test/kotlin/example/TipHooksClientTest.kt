package example

import example.backends.TIP_HOOK_SECRET
import example.backends.tipHooks
import example.backends.tipNoticed
import example.generated.tiphooks.ApiCallFailed
import example.generated.tiphooks.Tip
import example.generated.tiphooks.TipHooksClient
import io.github.matthewjones372.pelican.InMemoryClientTransport
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.standardWebhooks
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A generated client's signer signs the bytes it sends, and the endpoint it was generated from reads them. */
class TipHooksClientTest {

    private val served = api(listOf(tipNoticed handledNow { tip -> "noticed a tip of ${tip.amount}" }), JacksonCodecs)

    private val client = TipHooksClient("http://tips.example", JacksonCodecs, InMemoryClientTransport(served))

    @Test
    fun `a call signed with the endpoint's secret is read`() {
        client.tipNoticed(Tip(7), signer = tipHooks.signer(TIP_HOOK_SECRET)) shouldBe "noticed a tip of 7"
    }

    @Test
    fun `a call signed with another secret is refused with the declared 400`() {
        val stranger = standardWebhooks { emptyList() }.signer("whsec_bm90IHRoZSBqYXIncyBzZWNyZXQ=")

        shouldThrow<ApiCallFailed> { client.tipNoticed(Tip(7), signer = stranger) }.status shouldBe 400
    }
}
