package io.github.matthewjones372.pelican

/**
 * How a multi-valued parameter's values are spread across the wire. The same
 * [PlainCodec] read more than once; only the boundaries differ.
 *
 * These four are what OpenAPI can describe faithfully for a list of scalars.
 * `deepObject` is absent because it describes an object, not a list.
 */
enum class ListStyle(
    /**
     * What joins the values inside one occurrence, or null where the
     * occurrences are the boundary. Public because a generated client writes
     * the join into source it cannot call [encodeAll] from.
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
     * OpenAPI's `style` where the parameter travels. A comma is `form` in a
     * query and `simple` in a header: one wire shape, two names.
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
 * The `explode` OpenAPI assumes for [style]. False everywhere but `form` and
 * the `cookie` that 3.2 added beside it — the two places a document says
 * `explode: false` to get the comma rather than saying `true` to be rid of it.
 */
fun defaultExplodeFor(style: String): Boolean = style == "form" || style == "cookie"

private val simpleLocations = setOf("path", "header")

/**
 * Every value the wire carried under this name.
 *
 * [wire] is all the occurrences: a delimited style flattens them rather than
 * refusing the second, which is RFC 9110's rule for repeated header lines.
 *
 * Space around a separator is padding — RFC 9110 makes it optional in every
 * list-bearing header — so an element that really starts or ends with one
 * needs [ListStyle.REPEATED].
 */
fun PlainCodec<*>.decodeAll(name: String, style: ListStyle, wire: List<String>): List<Any> {
    val separator = style.separator
    val pieces = if (separator == null) wire else wire.flatMap { one -> one.split(separator).map(String::trim) }
    return pieces.mapNotNull { piece -> if (piece.isEmpty()) null else decode(name, piece) }
}

/**
 * The occurrences a list travels as: one per element for [ListStyle.REPEATED],
 * one joined string otherwise, none for an empty list.
 */
fun PlainCodec<*>.encodeAll(name: String, style: ListStyle, values: List<*>): List<String> {
    @Suppress("UNCHECKED_CAST")
    val codec = this as PlainCodec<Any>
    val encoded = values.filterNotNull().map { codec.encode(it) }
    encoded.forEach { value ->
        require(value.isNotEmpty()) {
            "'$name' was given an element that encodes to nothing, and an occurrence carrying nothing is " +
                "not an element — it would not come back. Leave it out of the list, or encode it as " +
                "something the other end can see."
        }
    }
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
 * The schema for a parameter carrying several [element]s. The example goes
 * inside `items`, because a parameter-level one would have to be an array and
 * choosing its length is not this module's decision.
 */
fun listSchema(element: PlainCodec<*>): JsonObj = jsonObj {
    "type" to "array"
    put(
        "items",
        element.openApiSchema() + (element.example?.let { jsonObj { "example" to it } } ?: emptyJsonObj),
    )
}
