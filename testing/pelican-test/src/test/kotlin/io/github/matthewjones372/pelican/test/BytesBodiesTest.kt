package io.github.matthewjones372.pelican.test

import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.Codecs
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.reflect.KType

class BytesBodiesTest {

    data class Receipt(val id: Long)

    private val receipt = endpoint {
        get("receipts")
        json<Receipt>()
    }

    /** JSON codecs that fail when asked to read [refused], so a test can see which form was decoded. */
    private class Refusing(private val inner: Codecs, private val refused: String) : Codecs by inner {
        override fun <T> codec(type: KType): BodyCodec<T> {
            val real = inner.codec<T>(type)
            return object : BodyCodec<T> by real {
                override fun decodeFromString(text: String): T =
                    if (refused == "text") error("decoded from a String") else real.decodeFromString(text)

                override fun decodeFrom(bytes: ByteArray): T =
                    if (refused == "bytes") error("decoded from bytes") else real.decodeFrom(bytes)
            }
        }
    }

    private fun clientAnswering(response: ResponseSpec, codecs: Codecs) = ApiClient(
        transport = object : Transport {
            override fun send(request: RequestSpec) = response
        },
        codecs = codecs,
    )

    @Test
    fun `a response built from bytes is decoded from those bytes`() {
        val bytes = ResponseSpec(200, emptyList(), """{"id":1}""".toByteArray())
        val client = clientAnswering(bytes, Refusing(JacksonCodecs, "text"))

        client.call(receipt, Unit) shouldBe Receipt(1)
    }

    @Test
    fun `a response built from a String is decoded from that String`() {
        val text = ResponseSpec(200, emptyList(), """{"id":1}""")
        val client = clientAnswering(text, Refusing(JacksonCodecs, "bytes"))

        client.call(receipt, Unit) shouldBe Receipt(1)
    }

    @Test
    fun `either form reads back as the other, as UTF-8`() {
        ResponseSpec(200, emptyList(), "héllo".toByteArray()).body shouldBe "héllo"
        ResponseSpec(200, emptyList(), "héllo").bytes shouldBe "héllo".toByteArray()
    }
}
