package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class MultiValuedInputsTest {

    // ----------------------------------------------------------- the styles

    @Test
    fun `a repeated parameter is one element per occurrence`() {
        val tag = queryParam<String>("tag").repeated()

        tag.decodeList(listOf("a", "b")) shouldBe listOf("a", "b")
    }

    @Test
    fun `the delimited styles split one occurrence on their own separator`() {
        queryParam<Int>("ids").commaSeparated().decodeList(listOf("1,2,3")) shouldBe listOf(1, 2, 3)
        queryParam<String>("f").spaceSeparated().decodeList(listOf("a b")) shouldBe listOf("a", "b")
        queryParam<String>("s").pipeSeparated().decodeList(listOf("a|b")) shouldBe listOf("a", "b")
    }

    @Test
    fun `a delimited style flattens repeated occurrences rather than refusing the second`() {
        // RFC 9110's rule for a header sent on two lines, applied everywhere:
        // two occurrences say what one comma-joined occurrence says.
        headerParam<String>("X-Tags").commaSeparated().decodeList(listOf("a,b", "c")) shouldBe
            listOf("a", "b", "c")
    }

    @Test
    fun `space around a separator is padding, as every list-bearing header treats it`() {
        headerParam<String>("X-Feature").commaSeparated().decodeList(listOf("beta, dark")) shouldBe
            listOf("beta", "dark")

        // Which is why a value that means to carry one cannot travel joined,
        // and repeated() is the declaration that can.
        queryParam<String>("tag").repeated().decodeList(listOf(" a ")) shouldBe listOf(" a ")
    }

    @Test
    fun `an occurrence carrying nothing contributes no element`() {
        // `?tags=` is what a form submits for a field nobody filled in.
        queryParam<String>("tags").commaSeparated().optional().decodeList(listOf("")).shouldBeNull()
        queryParam<String>("tags").repeated().optional().decodeList(listOf("a", "", "b")) shouldBe
            listOf("a", "b")
    }

    // ------------------------------------------------------ present or not

    @Test
    fun `a required list that nothing arrived for is the same 400 a required scalar gives`() {
        val required = queryParam<String>("tag").repeated()

        shouldThrow<ApiException> { required.decodeList(emptyList()) }
            .message shouldContain "Missing required query parameter 'tag'"
    }

    @Test
    fun `an optional list is null when absent, so required still means something`() {
        queryParam<String>("tag").repeated().optional().decodeList(emptyList()).shouldBeNull()
        queryParam<String>("tag").repeated().default(emptyList()).decodeList(emptyList()) shouldBe emptyList<String>()
    }

    @Test
    fun `the refusal names the location the value travels in`() {
        shouldThrow<ApiException> { headerParam<String>("X-Tags").commaSeparated().decodeList(emptyList()) }
            .message shouldContain "Missing required header 'X-Tags'"
        shouldThrow<ApiException> { cookieParam<String>("seen").repeated().decodeList(emptyList()) }
            .message shouldContain "Missing required cookie 'seen'"
    }

    // -------------------------------------------------------- the elements

    @Test
    fun `a refined element type rejects one bad element, and says which value`() {
        val ids = queryParam("ids", IntCodec.atLeast(1)).commaSeparated()

        ids.decodeList(listOf("1,2")) shouldBe listOf(1, 2)

        val rejected = shouldThrow<DecodeFailure> { ids.decodeList(listOf("1,0")) }
        rejected.raw shouldBe "0"
        rejected.expected shouldBe "a value of at least 1"
    }

    @Test
    fun `the element's refinement is what the schema documents`() {
        val schema = listSchema(IntCodec.atLeast(1))

        schema["type"] shouldBe JsonStr("array")
        (schema["items"] as JsonObj)["minimum"] shouldBe JsonNum(1)
    }

    // ------------------------------------------------------------ the wire

    @Test
    fun `encoding is the inverse of decoding, for each style`() {
        ListStyle.entries.forEach { style ->
            val wire = StringCodec.encodeAll("tag", style, listOf("a", "b"))
            StringCodec.decodeAll("tag", style, wire) shouldBe listOf("a", "b")
        }
    }

    @Test
    fun `an empty list is sent as nothing at all, whatever the style`() {
        ListStyle.entries.forEach { style ->
            StringCodec.encodeAll("tag", style, emptyList<String>()) shouldBe emptyList()
        }
    }

    @Test
    fun `an element containing the separator is refused rather than written`() {
        shouldThrow<IllegalArgumentException> {
            StringCodec.encodeAll("tag", ListStyle.COMMA, listOf("a,b"))
        }.message shouldContain "Declare the parameter as repeated()"

        // Which is exactly what repeated() can carry, since nothing is joined.
        StringCodec.encodeAll("tag", ListStyle.REPEATED, listOf("a,b")) shouldBe listOf("a,b")
    }

    @Test
    fun `an element carrying nothing is refused rather than written, whatever the style`() {
        ListStyle.entries.forEach { style ->
            shouldThrow<IllegalArgumentException> {
                StringCodec.encodeAll("tag", style, listOf("", "a"))
            }.message shouldContain "would not come back"
        }
    }

    @Test
    fun `an element padded with the space a reader would strip is refused too`() {
        shouldThrow<IllegalArgumentException> {
            StringCodec.encodeAll("tag", ListStyle.COMMA, listOf(" a"))
        }.message shouldContain "would come back trimmed"
    }

    // ---------------------------------------------------------- the cookie

    @Test
    fun `a repeated cookie is several pairs in the one header`() {
        val seen = cookieParam<String>("seen").repeated()
        val cookies = Cookies.parseAll(listOf(Cookies.render(listOf("seen" to "a", "seen" to "b"))))

        seen.decodeList(cookies.getValue("seen")) shouldBe listOf("a", "b")
    }

    @Test
    fun `reading one cookie still keeps the first spelling, as RFC 6265 orders them`() {
        Cookies.parse(listOf("seen=a; seen=b"))["seen"] shouldBe "a"
    }

    // ------------------------------------------------- declaring one twice

    @Test
    fun `a parameter cannot be spread twice, because there is no list of lists`() {
        shouldThrow<IllegalArgumentException> { queryParam<String>("tag").repeated().commaSeparated() }
            .message shouldContain "already carries a list"
    }
}
