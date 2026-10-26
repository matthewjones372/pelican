package io.github.matthewjones372.pelican.test

import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KType

class CodecsResolvedOnceTest {

    data class Receipt(val id: Long)

    private val receipt = jsonBody<Receipt>()

    private val echo = endpoint(receipt) {
        post("receipts")
        json<Receipt>()
    }

    private class Counting(private val inner: Codecs) : Codecs by inner {
        val asked = AtomicInteger()

        override fun <T> codec(type: KType): BodyCodec<T> {
            asked.incrementAndGet()
            return inner.codec(type)
        }
    }

    @Test
    fun `a client asks its codecs once for a type it writes and reads on every call`() {
        val codecs = Counting(JacksonCodecs)
        val client = ApiClient(
            transport = object : Transport {
                override fun send(request: RequestSpec) = ResponseSpec(200, emptyList(), request.body.orEmpty())
            },
            codecs = codecs,
        )

        repeat(3) { client.call(echo, Receipt(7)) shouldBe Receipt(7) }

        codecs.asked.get() shouldBe 1
    }
}
