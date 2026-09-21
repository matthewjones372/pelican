package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.math.BigInteger
import java.time.Duration

/**
 * The tree and the reader under it, which went untested for their whole life:
 * what they did was asserted only through documents that happened to travel
 * over them. See specs 0045 and 0047.
 */
class JsonValueTest {

    @Test
    fun `a document reads back as the value it was written from`() {
        val tree = jsonObj {
            "name" to "a widget"
            "count" to 3
            "ratio" to 1.5
            "sold" to true
            put("tags", jsonStrings(listOf("new", "sale")))
            put("origin", JsonNull)
            put("nested", jsonObj { "deep" to "yes" })
        }

        parseJson(tree.render()) shouldBe tree
    }

    /**
     * The reason `Long` comes first: rendering `1` back as `1.0` into a form
     * field where the sender wrote `1` is a round trip that changed the value.
     */
    @Test
    fun `a whole number stays whole`() {
        val parsed = parseJson("""{"n":1}""") as JsonObj

        (parsed["n"] as JsonNum).value.shouldBeInstanceOf<Long>()
        parsed.render() shouldBe """{"n":1}"""
    }

    @Test
    fun `an integer past Long keeps its digits rather than rounding into a Double`() {
        val beyondLong = "9223372036854775808"

        val parsed = parseJson("""{"n":$beyondLong}""") as JsonObj

        (parsed["n"] as JsonNum).value shouldBe BigInteger(beyondLong)
        withClue("a Double here would render 9223372036854776000") {
            parsed.render() shouldBe """{"n":$beyondLong}"""
        }
    }

    @Test
    fun `a value the grammar cannot spell cannot be built`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { value ->
            shouldThrow<IllegalArgumentException> { JsonNum(value) }.message shouldContain "NaN"
        }
        shouldThrow<IllegalArgumentException> { JsonNum(Float.NaN) }
    }

    /**
     * The document is well-formed and refused for its depth alone. Bounded in
     * the reader rather than by a check beside it, which is what makes reading
     * a message from somewhere unvouched-for safe — see `McpServer`.
     */
    @Test
    fun `nesting past the bound is refused rather than unwinding the stack`() {
        val deep = "[".repeat(DEEPER_THAN_ANY_DOCUMENT) + "]".repeat(DEEPER_THAN_ANY_DOCUMENT)

        assertTimeoutPreemptively(Duration.ofSeconds(TIMEOUT_SECONDS)) {
            shouldThrow<IllegalArgumentException> { parseJson(deep) }
        }
    }

    @Test
    fun `a document that is not one is refused, and says where`() {
        listOf("{not json", """{"a":}""", """{"a":1,}""", "", """{"a":1}{"b":2}""").forEach { bad ->
            withClue("`$bad` should not parse") { shouldThrow<IllegalArgumentException> { parseJson(bad) } }
        }
    }

    @Test
    fun `an escape survives the trip out and back`() {
        val awkward = "quote \" backslash \\ newline \n tab \t control \u0001 emoji 😀"

        parseJson(JsonStr(awkward).render()) shouldBe JsonStr(awkward)
    }

    private companion object {
        /** Deeper than the reader's bound, and than any document this reads. */
        const val DEEPER_THAN_ANY_DOCUMENT = 10_000

        /** A reader without a bound does not finish; it dies. Either way, not slowly. */
        const val TIMEOUT_SECONDS = 20L
    }
}
