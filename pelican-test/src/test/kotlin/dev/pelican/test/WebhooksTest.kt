package dev.pelican.test

import dev.pelican.Endpoint
import dev.pelican.Params
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.jsonBody
import dev.pelican.webhook
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * What this client does with a webhook: refuse it.
 *
 * The rest of Pelican keeps webhooks out of the routes by putting them in a
 * field of their own, which is what a server needs. A test client is handed
 * endpoint *values* directly, so the question is asked again here — and
 * answered twice over.
 *
 * `Webhook.operation` is an `Endpoint<*, *>`, and a star projection cannot be
 * passed to `request(endpoint: Endpoint<I, *>, input: I)` at all: the ordinary
 * way of making this mistake does not compile. The cast below is what it takes
 * to get past that, and the check underneath is there for it — a webhook has no
 * path, so the alternative is building `POST /` and asserting about a 404.
 */
class WebhooksTest {

    data class OrderEvent(val id: Long)

    private val event = jsonBody<OrderEvent>()

    private val orderPlaced = webhook("orderPlaced") {
        body(event)
        empty(status = 204)
    }

    private val client = ApiClient(
        transport = object : Transport {
            override fun send(request: RequestSpec) = ResponseSpec(204, emptyList(), "")
        },
        codecs = JacksonCodecs,
    )

    @Test
    fun `a webhook cannot be asked for, because nothing serves one`() {
        @Suppress("UNCHECKED_CAST")
        val cast = orderPlaced.operation as Endpoint<Params, *>

        shouldThrow<IllegalArgumentException> {
            client.request(cast, Params(emptyMap(), underlying = null))
        }.message.orEmpty() shouldContain "There is no route here to ask for it"
    }
}
