package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KType

class NegotiatedBodyTest {

    data class Order(val item: String)

    private object Schemas : Codecs {
        override fun <T> codec(type: KType): BodyCodec<T> {
            @Suppress("UNCHECKED_CAST")
            return object : BodyCodec<String> {
                override fun encodeToString(value: String) = value
                override fun decodeFromString(text: String) = "json:$text"
            } as BodyCodec<T>
        }

        override fun schema(type: KType, components: SchemaComponents): JsonObj = jsonObj {
            "type" to "object"
            put("properties", jsonObj { put("item", jsonObj { "type" to "string" }) })
        }
    }

    private val body = formBody<Order>(description = "The order") or jsonBody<Order>()

    // ------------------------------------------------------- what it is

    @Test
    fun `the alternatives keep the order they were declared in`() {
        body.alternatives.map { it.mediaType } shouldBe
            listOf("application/x-www-form-urlencoded", "application/json")
    }

    @Test
    fun `the description is the body's, taken from whichever alternative carried one`() {
        body.description shouldBe "The order"
    }

    @Test
    fun `a further alternative flattens rather than nesting`() {
        val failure = shouldThrow<IllegalArgumentException> {
            (jsonBody<Order>() or formBody<Order>()) or jsonBody<Order>()
        }

        withClue(failure.message) { failure.message shouldContain "application/json" }
    }

    // ------------------------------------------------- what it will not be

    @Test
    fun `alternatives carrying different types are refused where they are declared`() {
        val failure = shouldThrow<IllegalArgumentException> {
            @Suppress("UNCHECKED_CAST")
            jsonBody<Order>() or (formBody<String>() as BodyInput<Order>)
        }

        withClue(failure.message) {
            failure.message shouldContain "one value"
            failure.message shouldContain "discriminator"
        }
    }

    @Test
    fun `a body no codec reads is not an alternative to one that does`() {
        val failure = shouldThrow<IllegalArgumentException> {
            @Suppress("UNCHECKED_CAST")
            jsonBody<Order>() or (rawBody() as BodyInput<Order>)
        }

        withClue(failure.message) { failure.message shouldContain "rawBody()" }
    }

    @Test
    fun `two alternatives read the same way are refused, since nothing could pick between them`() {
        val failure = shouldThrow<IllegalArgumentException> { jsonBody<Order>() or jsonBody<Order>() }

        withClue(failure.message) { failure.message shouldContain "one Content-Type" }
    }

    // ------------------------------------------------ which codec a request gets

    @Test
    fun `the request's Content-Type picks the codec`() {
        val codecs = Schemas.requestBodyCodec(body)!!

        codecs.decode("application/json", "{}") shouldBe "json:{}"
        codecs.decode("application/x-www-form-urlencoded", "item=anvil") shouldBe "json:{\"item\":\"anvil\"}"
    }

    @Test
    fun `the parameters on a Content-Type are not part of the choice`() {
        val codecs = Schemas.requestBodyCodec(body)!!

        codecs.decode("Application/JSON; charset=utf-8", "{}") shouldBe "json:{}"
    }

    @Test
    fun `a media type the endpoint did not declare is a 415 naming the ones it did`() {
        val codecs = Schemas.requestBodyCodec(body)!!

        val failure = shouldThrow<UnsupportedMediaType> { codecs.decode("application/xml", "<order/>") }

        statusOfError(failure) shouldBe 415
        withClue(failure.detail) {
            failure.detail shouldContain "application/json"
            failure.detail shouldContain "application/x-www-form-urlencoded"
        }
    }

    @Test
    fun `no Content-Type at all is the same 415, because nothing says which decode was meant`() {
        val codecs = Schemas.requestBodyCodec(body)!!

        statusOfError(shouldThrow<UnsupportedMediaType> { codecs.decode(null, "{}") }) shouldBe 415
    }

    @Test
    fun `a body with one encoding still ignores the header, as it always did`() {
        val codecs = Schemas.requestBodyCodec(jsonBody<Order>())!!

        codecs.decode("text/plain", "{}") shouldBe "json:{}"
        codecs.decode(null, "{}") shouldBe "json:{}"
    }

    @Test
    fun `whatever the codec throws comes out as core's own failure`() {
        val broken = object : Codecs by Schemas {
            override fun <T> codec(type: KType): BodyCodec<T> {
                @Suppress("UNCHECKED_CAST")
                return object : BodyCodec<String> {
                    override fun encodeToString(value: String) = value
                    override fun decodeFromString(text: String): String = error("Jackson said no")
                } as BodyCodec<T>
            }
        }

        val failure = shouldThrow<BodyDecodeFailure> {
            broken.requestBodyCodec(jsonBody<Order>())!!.decode("application/json", "{}")
        }

        failure.message shouldContain "Jackson said no"
    }
}
