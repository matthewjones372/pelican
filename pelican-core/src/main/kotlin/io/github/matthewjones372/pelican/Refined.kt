package io.github.matthewjones372.pelican

import java.net.URI
import java.net.URISyntaxException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * Refinements for plain inputs. A [PlainCodec] says what a value parses as; a
 * refinement narrows it further, rejecting with a 400 before any handler sees
 * it *and* writing the constraint into the schema — so the document says what
 * the server enforces.
 *
 * See [map] and [mapOrFail] for the version that survives being passed around.
 */
internal class DerivedPlainCodec<A : Any, B : Any>(
    private val base: PlainCodec<A>,
    private val extraFacets: JsonObj = emptyJsonObj,
    private val describedBy: String? = null,
    private val exampleValue: String? = null,
    private val forward: (name: String, raw: String, value: A) -> B,
    private val backward: (B) -> A,
) : PlainCodec<B> {
    override val openApiType get() = base.openApiType
    override val openApiFormat get() = base.openApiFormat
    override val enumValues get() = base.enumValues
    override val schemaFacets get() = base.schemaFacets + extraFacets
    override val description get() = describedBy ?: base.description
    override val example get() = exampleValue ?: base.example

    override fun decode(name: String, raw: String): B = forward(name, raw, base.decode(name, raw))
    override fun encode(value: B): String = base.encode(backward(value))
}

/**
 * Accepts only values satisfying [predicate], and documents that with [facets].
 *
 * [expected] completes the sentence in the 400: `Cannot decode 'x' for
 * 'limit': expected a value of at least 1`.
 */
fun <T : Any> PlainCodec<T>.refine(
    expected: String,
    facets: JsonObj = emptyJsonObj,
    predicate: (T) -> Boolean,
): PlainCodec<T> = DerivedPlainCodec(
    base = this,
    extraFacets = facets,
    forward = { name, raw, value ->
        if (predicate(value)) value else throw DecodeFailure(name, raw, expected)
    },
    backward = { it },
)

/**
 * Derives a codec for a type of your own where the conversion can fail: return
 * null and the request is a 400 rather than an exception in a handler.
 */
fun <A : Any, B : Any> PlainCodec<A>.mapOrFail(
    expected: String,
    facets: JsonObj = emptyJsonObj,
    decode: (A) -> B?,
    encode: (B) -> A,
): PlainCodec<B> = DerivedPlainCodec(
    base = this,
    extraFacets = facets,
    forward = { name, raw, value -> decode(value) ?: throw DecodeFailure(name, raw, expected) },
    backward = encode,
)

/**
 * Documents the type rather than one use of it: every parameter built from the
 * returned codec carries this unless it says something of its own.
 */
fun <T : Any> PlainCodec<T>.describedAs(
    description: String? = null,
    example: String? = null,
): PlainCodec<T> = DerivedPlainCodec(
    base = this,
    describedBy = description,
    exampleValue = example,
    forward = { _, _, value -> value },
    backward = { it },
)

/** Adds schema facets without changing what is accepted — `format`, `example`, and such. */
fun <T : Any> PlainCodec<T>.withFacets(facets: JsonObj): PlainCodec<T> = DerivedPlainCodec(
    base = this,
    extraFacets = facets,
    forward = { _, _, value -> value },
    backward = { it },
)

// ------------------------------------------------------------------ strings

/** Rejects the empty string. Documents as `minLength: 1`. */
fun PlainCodec<String>.nonEmpty(): PlainCodec<String> =
    refine("a non-empty value", jsonObj { "minLength" to 1 }) { it.isNotEmpty() }

/** Rejects a value that is empty or only whitespace. */
fun PlainCodec<String>.nonBlank(): PlainCodec<String> =
    refine("a non-blank value", jsonObj { "minLength" to 1 }) { it.isNotBlank() }

fun PlainCodec<String>.minLength(min: Int): PlainCodec<String> =
    refine("at least $min characters", jsonObj { "minLength" to min }) { it.length >= min }

fun PlainCodec<String>.maxLength(max: Int): PlainCodec<String> =
    refine("at most $max characters", jsonObj { "maxLength" to max }) { it.length <= max }

/** Accepts only values [regex] matches in full. Documents as `pattern`. */
fun PlainCodec<String>.matching(
    regex: Regex,
    expected: String = "a value matching ${regex.pattern}",
): PlainCodec<String> =
    refine(expected, jsonObj { "pattern" to regex.pattern }) { regex.matches(it) }

// ------------------------------------------------------------------ numbers

fun <T> PlainCodec<T>.atLeast(min: T): PlainCodec<T> where T : Number, T : Comparable<T> =
    refine("a value of at least $min", jsonObj { put("minimum", JsonNum(min)) }) { it >= min }

fun <T> PlainCodec<T>.atMost(max: T): PlainCodec<T> where T : Number, T : Comparable<T> =
    refine("a value of at most $max", jsonObj { put("maximum", JsonNum(max)) }) { it <= max }

/** Both bounds at once, inclusive. */
fun <T> PlainCodec<T>.between(min: T, max: T): PlainCodec<T> where T : Number, T : Comparable<T> =
    refine(
        "a value between $min and $max",
        jsonObj {
            put("minimum", JsonNum(min))
            put("maximum", JsonNum(max))
        },
    ) { it in min..max }

/**
 * Rejects zero and negatives. The bound is the *value* of `exclusiveMinimum`,
 * which is what 3.1 means by it; 3.0 meant a boolean flag modifying `minimum`.
 */
fun <T> PlainCodec<T>.positive(): PlainCodec<T> where T : Number, T : Comparable<T> =
    refine("a positive value", jsonObj { put("exclusiveMinimum", JsonNum(0)) }) {
        it.toDouble() > 0.0
    }

// ------------------------------------------------------- ready-made types

/**
 * A string known not to be empty, because nothing else can be constructed.
 * Unlike a refinement, the type carries the guarantee onwards.
 */
@JvmInline
value class NonEmptyString private constructor(val value: String) {
    override fun toString() = value

    companion object {
        /** Null when [raw] is empty — the only way to make one. */
        fun of(raw: String): NonEmptyString? = if (raw.isEmpty()) null else NonEmptyString(raw)
    }
}

val NonEmptyStringCodec: PlainCodec<NonEmptyString> = StringCodec.mapOrFail(
    expected = "a non-empty value",
    facets = jsonObj { "minLength" to 1 },
    decode = NonEmptyString::of,
    encode = NonEmptyString::value,
)

object InstantCodec : PlainCodec<Instant> {
    override val openApiType = "string"
    override val openApiFormat = "date-time"
    override val example = "2026-08-19T09:30:00Z"
    override fun decode(name: String, raw: String): Instant =
        try { Instant.parse(raw) } catch (e: DateTimeParseException) {
            throw DecodeFailure(name, raw, "an ISO-8601 instant, e.g. $example")
        }
}

object LocalDateCodec : PlainCodec<LocalDate> {
    override val openApiType = "string"
    override val openApiFormat = "date"
    override val example = "2026-08-19"
    override fun decode(name: String, raw: String): LocalDate =
        try { LocalDate.parse(raw) } catch (e: DateTimeParseException) {
            throw DecodeFailure(name, raw, "an ISO-8601 date, e.g. $example")
        }
}

object LocalDateTimeCodec : PlainCodec<LocalDateTime> {
    override val openApiType = "string"
    override val openApiFormat = "date-time"
    override val example = "2026-08-19T09:30:00"
    override fun decode(name: String, raw: String): LocalDateTime =
        try { LocalDateTime.parse(raw) } catch (e: DateTimeParseException) {
            throw DecodeFailure(name, raw, "an ISO-8601 local date-time, e.g. $example")
        }
}

object UriCodec : PlainCodec<URI> {
    override val openApiType = "string"
    override val openApiFormat = "uri"
    override val example = "https://example.com/page"
    override fun decode(name: String, raw: String): URI =
        try { URI(raw) } catch (e: URISyntaxException) { throw DecodeFailure(name, raw, "a URI", e) }
}
