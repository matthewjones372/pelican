package dev.pelican

/**
 * How the several values of a multi-valued parameter are spread across the
 * wire.
 *
 * A [PlainCodec] answers what one string decodes to, and that is still the
 * whole of what an element means here — a repeated parameter is not a second
 * kind of codec, it is the same codec read more than once. What differs is
 * only where the boundaries between the values are: separate occurrences of
 * the name, or one occurrence with a character between the parts.
 *
 * The four below are the encodings OpenAPI can describe faithfully for a list
 * of scalars. Its `deepObject` is missing on purpose: it describes an object,
 * not a list, and there is no reading of it that a `List<T>` would not lose.
 */
enum class ListStyle(
    /**
     * What joins the values inside one occurrence, or null where the
     * occurrences themselves are the boundary. Public because a generated
     * client has to write the join into source it cannot call [encodeAll] from.
     */
    val separator: Char?,
) {
    /** `?tag=a&tag=b`, or `Cookie: tag=a; tag=b`. OpenAPI's `explode: true`. */
    REPEATED(null),

    /** `?tag=a,b`, and what a header carrying a list has always meant. */
    COMMA(','),

    /** `?tag=a%20b`. OpenAPI's `spaceDelimited`. */
    SPACE(' '),

    /** `?tag=a|b`. OpenAPI's `pipeDelimited`. */
    PIPE('|'),
    ;

    /** OpenAPI's `explode`, which is one bit of what a `style` says. */
    val explode: Boolean get() = this == REPEATED

    /**
     * OpenAPI's `style` for this encoding where the parameter travels. A comma
     * is `form` in a query string and `simple` in a header — the same wire
     * shape under two names, because OpenAPI names the encoding after the
     * place rather than after the separator.
     */
    fun styleAt(location: String): String = when {
        this == SPACE -> "spaceDelimited"
        this == PIPE -> "pipeDelimited"
        location in simpleLocations -> "simple"
        else -> "form"
    }
}

/** The `style` OpenAPI assumes at [location] when a parameter names none. */
fun defaultStyleAt(location: String): String = if (location in simpleLocations) "simple" else "form"

/**
 * The `explode` OpenAPI assumes for [style] when a parameter names none. It is
 * false everywhere except `form`, which is the one place a document has to say
 * `explode: false` to get the comma.
 */
fun defaultExplodeFor(style: String): Boolean = style == "form"

private val simpleLocations = setOf("path", "header")

/**
 * Every value the wire carried under this name, as the list it was declared to
 * be.
 *
 * [wire] is all of the occurrences, not the first: `?tag=a&tag=b` arrives as
 * two, and so does a header sent on two lines. A delimited style flattens them
 * rather than refusing the second, which is RFC 9110's own rule for repeated
 * header field lines and costs nothing to extend to a query string.
 *
 * Space around a separator is padding, not content. RFC 9110 makes it optional
 * in every list-bearing header, so `beta, dark` and `beta,dark` are the same
 * two values and reading the second element as `" dark"` would be a decode
 * failure nobody could see in the header. An element that really does begin or
 * end with a space cannot travel joined at all, and [ListStyle.REPEATED] is
 * the declaration that carries one.
 *
 * An empty piece contributes nothing. `?tags=` is how a form submits a field
 * nobody filled in, and reading it as a list holding one empty string would
 * hand a handler an element no caller meant to send — while a codec that
 * genuinely accepts the empty string, [StringCodec], is also the one where the
 * distinction is least visible.
 */
fun PlainCodec<*>.decodeAll(name: String, style: ListStyle, wire: List<String>): List<Any> {
    val separator = style.separator
    val pieces = if (separator == null) wire else wire.flatMap { one -> one.split(separator).map(String::trim) }
    return pieces.mapNotNull { piece -> if (piece.isEmpty()) null else decode(name, piece) }
}

/**
 * The occurrences a list travels as: one per element for [ListStyle.REPEATED],
 * one joined string otherwise, and none at all for an empty list.
 *
 * An element that the join would not survive is refused here rather than
 * written, because the list that came back would differ from the one that went
 * out and nothing downstream could tell. That is an element carrying the
 * separator, and one padded with the space that [decodeAll] reads as padding.
 */
fun PlainCodec<*>.encodeAll(name: String, style: ListStyle, values: List<*>): List<String> {
    @Suppress("UNCHECKED_CAST")
    val codec = this as PlainCodec<Any>
    val encoded = values.filterNotNull().map { codec.encode(it) }
    val separator = style.separator ?: return encoded
    if (encoded.isEmpty()) return emptyList()
    encoded.forEach { value ->
        require(separator !in value) {
            "'$name' joins its values with '$separator', and one of them contains it: '$value'. " +
                "Declare the parameter as repeated(), or encode the element so that it cannot."
        }
        require(value == value.trim()) {
            "'$name' joins its values with '$separator', and the space around one is padding rather " +
                "than content — so '$value' would come back trimmed. Declare the parameter as repeated()."
        }
    }
    return listOf(encoded.joinToString(separator.toString()))
}

/**
 * The schema for a parameter carrying several [element]s, as OpenAPI models
 * one.
 *
 * The element's example goes inside `items` rather than on the parameter,
 * because that is what it is an example of. A parameter-level `example` for an
 * array would have to be an array, and choosing a length for it would be this
 * module deciding something no description said.
 */
fun listSchema(element: PlainCodec<*>): JsonObj = jsonObj {
    "type" to "array"
    put(
        "items",
        element.openApiSchema() + (element.example?.let { jsonObj { "example" to it } } ?: emptyJsonObj),
    )
}

/**
 * What a multi-valued parameter contributes to a request's inputs, given every
 * occurrence the request carried under its name.
 *
 * The three locations share this rather than each interpreter spelling it out
 * nine times, because the part worth getting right is the same in all of them
 * and is not the obvious part: an occurrence carrying nothing is not an
 * element, so a list that comes out empty is a parameter the caller did not
 * send, and gets the same answer an absent scalar gets.
 */
fun QueryParam<*>.decodeList(wire: List<String>): Any? =
    listValue(name, codec, listStyle, required, default, "query parameter", wire)

fun HeaderParam<*>.decodeList(wire: List<String>): Any? =
    listValue(name, codec, listStyle, required, default, "header", wire)

fun CookieParam<*>.decodeList(wire: List<String>): Any? =
    listValue(name, codec, listStyle, required, default, "cookie", wire)

@Suppress("LongParameterList") // The declaration's facets, from three classes with no common supertype.
private fun listValue(
    name: String,
    codec: PlainCodec<*>,
    style: ListStyle?,
    required: Boolean,
    default: Any?,
    noun: String,
    wire: List<String>,
): Any? {
    val values = codec.decodeAll(name, checkNotNull(style) { "'$name' was not declared as a list" }, wire)
    return when {
        values.isNotEmpty() -> values
        required -> throw ApiException(400, "Missing required $noun '$name'")
        else -> default
    }
}
