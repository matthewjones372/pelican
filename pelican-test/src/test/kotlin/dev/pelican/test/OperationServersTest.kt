package dev.pelican.test

import dev.pelican.Method
import dev.pelican.endpoint
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.noInputs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * What this client does with an endpoint served somewhere else: nothing, on
 * purpose.
 *
 * A [RequestSpec] is a method, a path and a body, and it names no host —
 * because the transport is what decides where a request goes. In memory there
 * is no host to go to; against a live server the suite pointed the transport at
 * the one thing it is asserting about. Following a per-operation URL would send
 * that one call to a host nothing here is running, which is a worse answer than
 * ignoring a field that is documentation.
 *
 * A generated client does honour it, and that is not an inconsistency: it calls
 * a service somebody else is running, at the addresses that service's document
 * gave. This one calls the service under test.
 */
class OperationServersTest {

    data class Receipt(val id: Long)

    private val here = endpoint(noInputs) {
        get("receipts")
        json<Receipt>()
    }

    private val elsewhere = endpoint(noInputs) {
        get("receipts")
        servers("https://uploads.example.com")
        json<Receipt>()
    }

    private val client = ApiClient(
        transport = object : Transport {
            override fun send(request: RequestSpec) = ResponseSpec(200, emptyList(), """{"id":1}""")
        },
        codecs = JacksonCodecs,
    )

    @Test
    fun `the request is the same one either way, and carries no host`() {
        val declared = client.request(elsewhere, Unit)

        declared.method shouldBe Method.GET
        declared.target shouldBe client.request(here, Unit).target
        declared.target shouldBe "/receipts"
    }

    @Test
    fun `the call still goes to the transport, which is where the suite pointed it`() {
        client.call(elsewhere, Unit) shouldBe Receipt(1)
    }
}
