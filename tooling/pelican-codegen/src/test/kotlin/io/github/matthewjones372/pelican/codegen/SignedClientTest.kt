package io.github.matthewjones372.pelican.codegen

import io.github.matthewjones372.pelican.apiSpec
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jsonBody
import io.github.matthewjones372.pelican.standardWebhooks
import io.github.matthewjones372.pelican.webhook
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** A signed endpoint or webhook is called with a signer, which signs the bytes that go out. Spec 0097. */
class SignedClientTest {

    private val hooks = standardWebhooks { listOf("whsec_c2VjcmV0") }

    private val received = endpoint(jsonBody<KotlinClientTest.Widget>()) {
        post("hooks")
        operationId = "receive"
        signedBy(hooks)
        text()
    }

    private val sent = webhook("widgetMade") {
        body(jsonBody<KotlinClientTest.Widget>())
        signedBy(hooks)
        empty(status = 204)
    }

    private val client =
        apiSpec(listOf(received), KotlinClientTest.WidgetSchemas) { title = "Hooks"; webhooks = listOf(sent) }
            .kotlinClient("com.example")

    @Test
    fun `a signed endpoint's method takes a signer, handed to the request it builds`() {
        client shouldContain "fun receive(body: Widget, signer: WebhookSigner)"
        client shouldContain "signer = signer)"
    }

    @Test
    fun `so does a signed webhook's sender`() {
        client shouldContain "fun widgetMade(url: String, body: Widget, signer: WebhookSigner)"
    }

    @Test
    fun `the signature headers are the signer's, not parameters of their own`() {
        client shouldNotContain "webhookSignature"
    }
}
