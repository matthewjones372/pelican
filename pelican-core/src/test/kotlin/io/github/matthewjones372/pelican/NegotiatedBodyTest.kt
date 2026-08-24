package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.reflect.KType

/**
 * One payload, several encodings — the half of "two media types for one body"
 * that turned out to be describable.
 *
 * The line this draws is the whole design, so it is where the tests are. An
 * endpoint hands its handler one value of one type; several *encodings* of that
 * value is a choice about decoding, which a `Content-Type` can make, and several
 * *shapes* is a choice about what the payload is, which it cannot. So the first
 * is a body and the second is still refused — by the type system where a
 * description is written by hand, and by the importer where it is read.
 *
 * The codec here hands back whatever it was given, for the same reason
 * `FormBodyTest`'s does: which library can turn a document into a data class is
 * tested where that library is, and what is being asserted on here is which
 * codec a request reaches at all.
 */
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
        // `a or b or c` associates to the left, and a pair holding a pair would
        // leave "the first alternative" — which is what a client sends —
        // depending on where the parentheses fell. The observable consequence
        // is this one: the third is compared against both of the first two, so
        // an encoding already in there is caught rather than buried a level
        // down where nothing would look at it again.
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

        val failure = shouldThrow<ApiException> { codecs.decode("application/xml", "<order/>") }

        failure.status shouldBe 415
        val detail = failure.detail.orEmpty()
        withClue(detail) {
            detail shouldContain "application/json"
            detail shouldContain "application/x-www-form-urlencoded"
        }
    }

    @Test
    fun `no Content-Type at all is the same 415, because nothing says which decode was meant`() {
        val codecs = Schemas.requestBodyCodec(body)!!

        shouldThrow<ApiException> { codecs.decode(null, "{}") }.status shouldBe 415
    }

    @Test
    fun `a body with one encoding still ignores the header, as it always did`() {
        // The 415 is only for a body that declared a choice. With one encoding
        // the header carries no information this reader needs, and refusing a
        // request over it would break every caller that has been sending a JSON
        // body with no Content-Type since before there was anything to choose.
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
