package dev.pelican

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Refinements do two jobs at once, and both are asserted here: the value is
 * rejected before a handler sees it, and the constraint appears in the schema.
 * A refinement that enforced without documenting would make the spec a lie in
 * the other direction from the error-body one.
 */
class RefinedInputsTest {

    private fun decode(codec: PlainCodec<*>, raw: String) = codec.decode("p", raw)

    private fun rejects(codec: PlainCodec<*>, raw: String): DecodeFailure =
        assertThrows(DecodeFailure::class.java) { decode(codec, raw) }

    // -------------------------------------------------------------- strings

    @Test
    fun `nonEmpty rejects the empty string and documents minLength`() {
        val codec = StringCodec.nonEmpty()
        assertEquals("x", decode(codec, "x"))
        assertEquals("a non-empty value", rejects(codec, "").expected)
        assertEquals(JsonNum(1), codec.openApiSchema()["minLength"])
    }

    @Test
    fun `nonBlank rejects whitespace, which nonEmpty allows`() {
        assertEquals(" ", decode(StringCodec.nonEmpty(), " "))
        rejects(StringCodec.nonBlank(), " ")
    }

    @Test
    fun `matching enforces the whole value and documents the pattern`() {
        val codec = StringCodec.matching(Regex("[a-z-]+"), "a slug")
        assertEquals("open-source", decode(codec, "open-source"))
        rejects(codec, "Open Source")
        rejects(codec, "abc1")   // matches() is anchored, unlike containsMatchIn
        assertEquals(JsonStr("[a-z-]+"), codec.openApiSchema()["pattern"])
    }

    @Test
    fun `length bounds compose`() {
        val codec = StringCodec.minLength(2).maxLength(4)
        assertEquals("abc", decode(codec, "abc"))
        rejects(codec, "a")
        rejects(codec, "abcde")
        assertEquals(JsonNum(2), codec.openApiSchema()["minLength"])
        assertEquals(JsonNum(4), codec.openApiSchema()["maxLength"])
    }

    // -------------------------------------------------------------- numbers

    @Test
    fun `between bounds the value at both ends and documents both`() {
        val codec = IntCodec.between(1, 100)
        assertEquals(50, decode(codec, "50"))
        assertEquals("a value between 1 and 100", rejects(codec, "0").expected)
        rejects(codec, "101")

        val schema = codec.openApiSchema()
        assertEquals(JsonNum(1), schema["minimum"])
        assertEquals(JsonNum(100), schema["maximum"])
        // The underlying codec still decides what parses at all.
        assertEquals("a 32-bit integer", rejects(codec, "many").expected)
        assertEquals(JsonStr("integer"), schema["type"])
    }

    @Test
    fun `positive rejects zero`() {
        rejects(LongCodec.positive(), "0")
        assertEquals(1L, decode(LongCodec.positive(), "1"))
    }

    // ---------------------------------------------------------- own types

    @JvmInline
    value class Email(val value: String)

    private val emailCodec = StringCodec.mapOrFail(
        expected = "an email address",
        facets = jsonObj { "format" to "email" },
        decode = { raw -> if ("@" in raw) Email(raw) else null },
        encode = Email::value,
    ).describedAs("Where the receipt is sent", example = "ada@example.com")

    @Test
    fun `mapOrFail turns a rejected value into a decode failure, not an exception`() {
        assertEquals(Email("ada@example.com"), decode(emailCodec, "ada@example.com"))
        assertEquals("an email address", rejects(emailCodec, "nope").expected)
    }

    @Test
    fun `a codec can document itself, and encode round-trips`() {
        assertEquals("Where the receipt is sent", emailCodec.description)
        assertEquals("ada@example.com", emailCodec.example)
        assertEquals(JsonStr("email"), emailCodec.openApiSchema()["format"])
        assertEquals("ada@example.com", emailCodec.encode(Email("ada@example.com")))
    }

    @Test
    fun `NonEmptyString cannot be constructed empty`() {
        assertNull(NonEmptyString.of(""))
        assertEquals("rope", NonEmptyString.of("rope")!!.value)
        assertEquals("a non-empty value", rejects(NonEmptyStringCodec, "").expected)
        assertEquals(NonEmptyString.of("rope"), decode(NonEmptyStringCodec, "rope"))
    }

    // ------------------------------------------------------------ built-ins

    @Test
    fun `time and uri codecs parse, document a format, and fail as a 400`() {
        assertEquals(Instant.parse("2026-08-19T09:30:00Z"), decode(InstantCodec, "2026-08-19T09:30:00Z"))
        assertEquals(LocalDate.of(2026, 8, 19), decode(LocalDateCodec, "2026-08-19"))
        assertEquals(JsonStr("date-time"), InstantCodec.openApiSchema()["format"])
        assertEquals(JsonStr("date"), LocalDateCodec.openApiSchema()["format"])
        assertEquals(JsonStr("uri"), UriCodec.openApiSchema()["format"])
        rejects(InstantCodec, "yesterday")
        rejects(LocalDateCodec, "19/08/2026")
    }

    @Test
    fun `plainCodecFor resolves the built-ins by type`() {
        assertSame(InstantCodec, plainCodecFor<Instant>())
        assertSame(LocalDateCodec, plainCodecFor<LocalDate>())
        assertSame(NonEmptyStringCodec, plainCodecFor<NonEmptyString>())
    }

    // ------------------------------------------------------------ endpoints

    @Test
    fun `a refined parameter carries its constraint into the endpoint description`() {
        val page = queryParam("page", IntCodec.atLeast(1), description = "1-based")
        val ep = endpoint(page) {
            get("things")
            json<String>()
        }

        val schema = ep.queries.single().codec.openApiSchema()
        assertEquals(JsonNum(1), schema["minimum"])
    }
}
