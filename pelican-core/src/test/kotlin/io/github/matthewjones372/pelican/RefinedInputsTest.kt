package io.github.matthewjones372.pelican

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
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
        shouldThrow<DecodeFailure> { decode(codec, raw) }

    // -------------------------------------------------------------- strings

    @Test
    fun `nonEmpty rejects the empty string and documents minLength`() {
        val codec = StringCodec.nonEmpty()
        decode(codec, "x") shouldBe "x"
        rejects(codec, "").expected shouldBe "a non-empty value"
        codec.openApiSchema()["minLength"] shouldBe JsonNum(1)
    }

    @Test
    fun `nonBlank rejects whitespace, which nonEmpty allows`() {
        decode(StringCodec.nonEmpty(), " ") shouldBe " "
        rejects(StringCodec.nonBlank(), " ")
    }

    @Test
    fun `matching enforces the whole value and documents the pattern`() {
        val codec = StringCodec.matching(Regex("[a-z-]+"), "a slug")
        decode(codec, "open-source") shouldBe "open-source"
        rejects(codec, "Open Source")
        rejects(codec, "abc1")   // matches() is anchored, unlike containsMatchIn
        codec.openApiSchema()["pattern"] shouldBe JsonStr("[a-z-]+")
    }

    @Test
    fun `length bounds compose`() {
        val codec = StringCodec.minLength(2).maxLength(4)
        decode(codec, "abc") shouldBe "abc"
        rejects(codec, "a")
        rejects(codec, "abcde")
        codec.openApiSchema()["minLength"] shouldBe JsonNum(2)
        codec.openApiSchema()["maxLength"] shouldBe JsonNum(4)
    }

    // -------------------------------------------------------------- numbers

    @Test
    fun `between bounds the value at both ends and documents both`() {
        val codec = IntCodec.between(1, 100)
        decode(codec, "50") shouldBe 50
        rejects(codec, "0").expected shouldBe "a value between 1 and 100"
        rejects(codec, "101")

        val schema = codec.openApiSchema()
        schema["minimum"] shouldBe JsonNum(1)
        schema["maximum"] shouldBe JsonNum(100)
        // The underlying codec still decides what parses at all.
        rejects(codec, "many").expected shouldBe "a 32-bit integer"
        schema["type"] shouldBe JsonStr("integer")
    }

    @Test
    fun `positive rejects zero`() {
        rejects(LongCodec.positive(), "0")
        decode(LongCodec.positive(), "1") shouldBe 1L
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
        decode(emailCodec, "ada@example.com") shouldBe Email("ada@example.com")
        rejects(emailCodec, "nope").expected shouldBe "an email address"
    }

    @Test
    fun `a codec can document itself, and encode round-trips`() {
        emailCodec.description shouldBe "Where the receipt is sent"
        emailCodec.example shouldBe "ada@example.com"
        emailCodec.openApiSchema()["format"] shouldBe JsonStr("email")
        emailCodec.encode(Email("ada@example.com")) shouldBe "ada@example.com"
    }

    @Test
    fun `NonEmptyString cannot be constructed empty`() {
        NonEmptyString.of("").shouldBeNull()
        NonEmptyString.of("rope")!!.value shouldBe "rope"
        rejects(NonEmptyStringCodec, "").expected shouldBe "a non-empty value"
        decode(NonEmptyStringCodec, "rope") shouldBe NonEmptyString.of("rope")
    }

    // ------------------------------------------------------------ built-ins

    @Test
    fun `time and uri codecs parse, document a format, and fail as a 400`() {
        decode(InstantCodec, "2026-08-19T09:30:00Z") shouldBe Instant.parse("2026-08-19T09:30:00Z")
        decode(LocalDateCodec, "2026-08-19") shouldBe LocalDate.of(2026, 8, 19)
        InstantCodec.openApiSchema()["format"] shouldBe JsonStr("date-time")
        LocalDateCodec.openApiSchema()["format"] shouldBe JsonStr("date")
        UriCodec.openApiSchema()["format"] shouldBe JsonStr("uri")
        rejects(InstantCodec, "yesterday")
        rejects(LocalDateCodec, "19/08/2026")
    }

    @Test
    fun `plainCodecFor resolves the built-ins by type`() {
        plainCodecFor<Instant>() shouldBeSameInstanceAs InstantCodec
        plainCodecFor<LocalDate>() shouldBeSameInstanceAs LocalDateCodec
        plainCodecFor<NonEmptyString>() shouldBeSameInstanceAs NonEmptyStringCodec
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
        schema["minimum"] shouldBe JsonNum(1)
    }
}
