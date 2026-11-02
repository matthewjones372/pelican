package dev.surefold.pelican.test

import dev.surefold.pelican.Endpoint
import dev.surefold.pelican.Params
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.jsonBody
import dev.surefold.pelican.webhook
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

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
