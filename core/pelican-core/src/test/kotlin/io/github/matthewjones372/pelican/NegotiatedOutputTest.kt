package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.acceptable
import io.github.matthewjones372.pelican.spi.selectedFor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf

class NegotiatedOutputTest {

    data class Report(val year: Int, val total: Long)

    private val asJson = json<Report>(200)
    private val asCsv = media<Report>("text/csv", 200)
    private val export = negotiated(asJson, asCsv)

    // ------------------------------------------------------------ what it is

    @Test
    fun `the alternatives keep the order they were declared in`() {
        export.alternatives.map { it.mediaType } shouldBe listOf("application/json", "text/csv")
    }

    @Test
    fun `one status, because it is one response`() {
        export.status shouldBe 200
        export.payloadType shouldBe typeOf<Report>()
    }

    /** What the pre-handler 406 is answered against; see [acceptable]. */
    @Test
    fun `every representation it offers is one it produces`() {
        export.produces shouldBe setOf("application/json", "text/csv")

        acceptable(listOf("text/csv"), export.produces) shouldBe true
        acceptable(listOf("application/xml"), export.produces) shouldBe false
    }

    // ------------------------------------------------------- which one goes out

    @Test
    fun `a caller that asks for one of them gets that one`() {
        export.selectedFor(listOf("text/csv")) shouldBeSameInstanceAs asCsv
        export.selectedFor(listOf("application/json")) shouldBeSameInstanceAs asJson
    }

    @Test
    fun `no Accept at all takes the first declared`() {
        export.selectedFor(emptyList()) shouldBeSameInstanceAs asJson
        negotiated(asCsv, asJson).selectedFor(emptyList()) shouldBeSameInstanceAs asCsv
    }

    @Test
    fun `a wildcard takes the first declared too, since it asked for nothing in particular`() {
        export.selectedFor(listOf("*/*")) shouldBeSameInstanceAs asJson
    }

    @Test
    fun `a q value is a preference, and the preferred one goes out`() {
        export.selectedFor(listOf("application/json;q=0.2, text/csv;q=0.9")) shouldBeSameInstanceAs asCsv
        export.selectedFor(listOf("application/json;q=0.9, text/csv;q=0.2")) shouldBeSameInstanceAs asJson
    }

    /** RFC 9110 §12.5.1: the most specific range decides, whatever order it was written in. */
    @Test
    fun `a named type beats the wildcard beside it`() {
        export.selectedFor(listOf("*/*;q=0.1, text/csv")) shouldBeSameInstanceAs asCsv
    }

    @Test
    fun `a caller who will take none of them is refused, not sent one anyway`() {
        val refused = shouldThrow<NotAcceptable> { export.selectedFor(listOf("application/xml")) }

        withClue(refused.message) {
            refused.produced shouldBe setOf("application/json", "text/csv")
        }
    }

    /**
     * `q=0` is "not this one" rather than "this one, weakly", so a caller
     * excluding the only type on offer is refused.
     */
    @Test
    fun `a representation refused by weight is not the one that goes out`() {
        export.selectedFor(listOf("application/json;q=0, text/csv")) shouldBeSameInstanceAs asCsv
        shouldThrow<NotAcceptable> { export.selectedFor(listOf("application/json;q=0, text/csv;q=0")) }
    }

    // ------------------------------------------------ beside the other responses

    @Test
    fun `several renderings of one response are one response, so the status clash rule leaves them alone`() {
        val declared = export or empty(202)

        declared.successes.map { it.status } shouldBe listOf(200, 202)
    }

    @Test
    fun `a second response under the group's status is still refused`() {
        val clash = shouldThrow<IllegalArgumentException> { export or json<Report>(200) }

        withClue(clash.message) {
            clash.message shouldContain "Two responses are declared for status 200"
            clash.message shouldContain "negotiated("
        }
    }

    // ------------------------------------------------------- what it will not be

    @Test
    fun `one alternative is not a negotiation`() {
        val refused = shouldThrow<IllegalArgumentException> { negotiated(asJson) }

        withClue(refused.message) { refused.message shouldContain "one representation" }
    }

    @Test
    fun `alternatives that disagree about the status are refused where they are declared`() {
        val refused = shouldThrow<IllegalArgumentException> {
            negotiated(json<Report>(200), media<Report>("text/csv", 201))
        }

        withClue(refused.message) { refused.message shouldContain "one response" }
    }

    @Test
    fun `two alternatives written the same way are refused, since nothing could pick between them`() {
        val refused = shouldThrow<IllegalArgumentException> {
            negotiated(json<Report>(200), media<Report>("application/json", 200))
        }

        withClue(refused.message) { refused.message shouldContain "application/json" }
    }

    @Test
    fun `alternatives carrying different types are refused, since it is one value`() {
        val refused = shouldThrow<IllegalArgumentException> {
            @Suppress("UNCHECKED_CAST")
            negotiated(json<Report>(200), media<String>("text/csv", 200) as Output<Report>)
        }

        withClue(refused.message) {
            refused.message shouldContain "one value"
            refused.message shouldContain "different statuses"
        }
    }

    @Test
    fun `a response with no described payload is not a rendering of one`() {
        val refused = shouldThrow<IllegalArgumentException> {
            @Suppress("UNCHECKED_CAST")
            negotiated(json<Report>(200), empty(200) as Output<Report>)
        }

        withClue(refused.message) { refused.message shouldContain "empty:200" }
    }

    @Test
    fun `a streamed alternative is refused, because producing one hands over a stream`() {
        val refused = shouldThrow<IllegalArgumentException> {
            endpoint {
                get("reports")
                @Suppress("UNCHECKED_CAST")
                negotiated(json<Report>(200), ndjson<Report>(200) as Output<Report>)
            }
        }

        withClue(refused.message) { refused.message shouldContain "streams" }
    }

    @Test
    fun `a nested group flattens rather than nesting, so the first is the first`() {
        val three = negotiated(export, media<Report>("text/tab-separated-values", 200))

        three.alternatives.map { it.mediaType } shouldBe
            listOf("application/json", "text/csv", "text/tab-separated-values")
    }

    // ------------------------------------------------------------ what writes it

    @Test
    fun `a media type nothing writes is refused where the codec is resolved, not on the wire`() {
        val refused = shouldThrow<IllegalStateException> { JsonOnly.codec<Report>(typeOf<Report>(), "text/csv") }

        withClue(refused.message) {
            refused.message shouldContain "text/csv"
            refused.message shouldContain "Codecs"
        }
    }

    @Test
    fun `and JSON is the codec it already had`() {
        JsonOnly.codec<Report>(typeOf<Report>(), "application/json") shouldBeSameInstanceAs JsonOnly.only
    }

    /** A codec factory with the one encoding every JSON library gives it. */
    private object JsonOnly : CodecFactory {
        val only = object : BodyCodec<Any?> {
            override fun encodeToString(value: Any?) = "{}"
            override fun decodeFromString(text: String) = null
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T> codec(type: kotlin.reflect.KType): BodyCodec<T> = only as BodyCodec<T>
    }
}
