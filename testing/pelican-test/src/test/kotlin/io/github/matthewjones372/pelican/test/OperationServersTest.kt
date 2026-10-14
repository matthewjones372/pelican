package io.github.matthewjones372.pelican.test

import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class OperationServersTest {

    data class Receipt(val id: Long)

    private val here = endpoint {
        get("receipts")
        json<Receipt>()
    }

    private val elsewhere = endpoint {
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
