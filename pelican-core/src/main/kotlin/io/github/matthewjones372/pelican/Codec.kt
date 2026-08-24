package io.github.matthewjones372.pelican

import java.util.UUID

/** Thrown when a path/query/header value cannot be decoded into its declared type. */
class DecodeFailure(
    val paramName: String,
    val raw: String,
    val expected: String,
    cause: Throwable? = null,
) : RuntimeException("Cannot decode '$raw' for '$paramName': expected $expected", cause)

/**
 * Codec for a value that travels as a single string: a path segment, a query
 * parameter or a header. Carries just enough metadata to also describe itself
 * in OpenAPI.
 *
 * A parameter carrying several values is still described by one of these: what
 * an element decodes to is the same question, and only where the boundaries
 * between the values sit is new. See [ListStyle].
 */
interface PlainCodec<T : Any> {
    /** OpenAPI primitive type: "string", "integer", "number", "boolean". */
    val openApiType: String

    /** OpenAPI format hint, e.g. "int64", "uuid". Null when there isn't one. */
    val openApiFormat: String? get() = null

    /** Allowed values, for enums. Null when unconstrained. */
    val enumValues: List<String>? get() = null

    /**
     * Whatever else the schema should say: `minLength`, `pattern`, `minimum`.
     *
     * A refinement rejects the value *and* documents why it would — see
     * [refine]. Without this the document would promise `type: string` for a
     * parameter the server will only accept non-empty.
     */
    val schemaFacets: JsonObj get() = emptyJsonObj

    /**
     * What values of this type mean, for parameters that do not say it
     * themselves. A parameter's own `description` wins; this is what a shared
     * type — an `Email`, a `PageSize` — carries with it everywhere it is used.
     */
    val description: String? get() = null

    /** An example value, rendered on the parameter. */
    val example: String? get() = null

    fun decode(name: String, raw: String): T
    fun encode(value: T): String = value.toString()
}

object StringCodec : PlainCodec<String> {
    override val openApiType = "string"
    override fun decode(name: String, raw: String) = raw
}

object IntCodec : PlainCodec<Int> {
    override val openApiType = "integer"
    override val openApiFormat = "int32"
    override fun decode(name: String, raw: String) =
        raw.toIntOrNull() ?: throw DecodeFailure(name, raw, "a 32-bit integer")
}

object LongCodec : PlainCodec<Long> {
    override val openApiType = "integer"
    override val openApiFormat = "int64"
    override fun decode(name: String, raw: String) =
        raw.toLongOrNull() ?: throw DecodeFailure(name, raw, "a 64-bit integer")
}

object DoubleCodec : PlainCodec<Double> {
    override val openApiType = "number"
    override val openApiFormat = "double"
    override fun decode(name: String, raw: String) =
        raw.toDoubleOrNull() ?: throw DecodeFailure(name, raw, "a number")
}

object BooleanCodec : PlainCodec<Boolean> {
    override val openApiType = "boolean"
    override fun decode(name: String, raw: String) = when (raw.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> throw DecodeFailure(name, raw, "a boolean")
    }
}

object UuidCodec : PlainCodec<UUID> {
    override val openApiType = "string"
    override val openApiFormat = "uuid"
    override fun decode(name: String, raw: String): UUID =
        try { UUID.fromString(raw) } catch (e: IllegalArgumentException) {
            throw DecodeFailure(name, raw, "a UUID", e)
        }
}

/** Codec for a Kotlin enum, matched case-insensitively against its constant names. */
class EnumCodec<T : Any>(
    private val constants: Array<out T>,
    private val nameOf: (T) -> String,
) : PlainCodec<T> {
    override val openApiType = "string"
    override val enumValues = constants.map(nameOf)
    override fun decode(name: String, raw: String): T =
        constants.firstOrNull { nameOf(it).equals(raw, ignoreCase = true) }
            ?: throw DecodeFailure(name, raw, "one of ${enumValues.joinToString("|")}")

    override fun encode(value: T): String = nameOf(value)
}

/** Resolves the built-in codec for [T]. Enums are supported automatically. */
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> plainCodecFor(): PlainCodec<T> {
    val cls = T::class.java
    if (cls.isEnum) {
        return EnumCodec(cls.enumConstants as Array<out T>) { (it as Enum<*>).name }
    }
    return when (T::class) {
        String::class -> StringCodec

        Int::class -> IntCodec

        Long::class -> LongCodec

        Double::class -> DoubleCodec

        Boolean::class -> BooleanCodec

        UUID::class -> UuidCodec

        NonEmptyString::class -> NonEmptyStringCodec

        java.time.Instant::class -> InstantCodec

        java.time.LocalDate::class -> LocalDateCodec

        java.time.LocalDateTime::class -> LocalDateTimeCodec

        java.net.URI::class -> UriCodec

        else -> throw IllegalArgumentException(
            "No PlainCodec for ${T::class.qualifiedName}. Pass one explicitly.",
        )
    } as PlainCodec<T>
}

/**
 * Derives a codec for a wrapper type.
 *
 * Worth doing when an endpoint takes two inputs of the same primitive type:
 * `endpoint(userId, orderId)` as two `Long`s will happily accept a handler that
 * reads them in the wrong order, because the compiler cannot tell them apart.
 * Wrap them and it can:
 *
 * ```
 * @JvmInline value class UserId(val value: Long)
 * @JvmInline value class OrderId(val value: Long)
 *
 * val userId  = pathParam("userId",  LongCodec.map(::UserId,  UserId::value))
 * val orderId = pathParam("orderId", LongCodec.map(::OrderId, OrderId::value))
 * ```
 */
fun <A : Any, B : Any> PlainCodec<A>.map(decode: (A) -> B, encode: (B) -> A): PlainCodec<B> =
    DerivedPlainCodec(this, forward = { _, _, a -> decode(a) }, backward = encode)

/** OpenAPI schema fragment for a plain (string-carried) parameter. */
fun PlainCodec<*>.openApiSchema(): JsonObj = jsonObj {
    "type" to openApiType
    putIfNotNull("format", openApiFormat)
    enumValues?.let { put("enum", jsonStrings(it)) }
} + schemaFacets
