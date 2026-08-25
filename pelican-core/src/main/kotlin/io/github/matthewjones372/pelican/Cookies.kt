package io.github.matthewjones372.pelican

/**
 * Reads and writes the `Cookie` request header.
 *
 * The three backends each have a cookie API and each behaves differently — one
 * unquotes, another splits on `,` as well as `;` — so the splitting lives here
 * and they only hand over the header.
 *
 * Values travel exactly as written: RFC 6265 already excludes `;`, `,`, space
 * and control characters, and percent-decoding would corrupt a value
 * containing a `%`. [render] refuses a value a cookie cannot carry.
 */
object Cookies {

    /**
     * The cookies in these header values, first spelling winning: RFC 6265
     * orders them most-specific first, so a later duplicate is the one to drop.
     */
    fun parse(headers: List<String>): Map<String, String> =
        parseAll(headers).mapValues { (_, values) -> values.first() }

    fun parse(header: String?): Map<String, String> = parse(listOfNotNull(header))

    /**
     * The same, keeping every spelling of a name. For a cookie declared as a
     * list that repetition is the list — RFC 6265 leaves no other encoding,
     * since a cookie value cannot carry a comma. [parse] is defined on this.
     */
    fun parseAll(headers: List<String>): Map<String, List<String>> =
        headers
            .flatMap { it.split(';') }
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                val name = if (separator > 0) pair.substring(0, separator).trim() else ""
                if (name.isEmpty()) null else name to unquote(pair.substring(separator + 1).trim())
            }
            .groupBy({ it.first }, { it.second })

    /** The `Cookie` header value carrying these, as a client would send it. */
    fun render(cookies: List<Pair<String, String>>): String =
        cookies.joinToString("; ") { (name, value) ->
            require(value.none { it == ';' || it == ',' || it == ' ' || it < ' ' }) {
                "A cookie value cannot contain ';', ',', a space or a control character, " +
                    "and '$name' does: ${value.take(COOKIE_VALUE_PREVIEW)}"
            }
            "$name=$value"
        }

    /**
     * A quoted cookie value, unquoted. Servers write `name="value"` often
     * enough that keeping the quotes would fail decodes invisibly.
     */
    private fun unquote(value: String): String =
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value.substring(1, value.length - 1)
        else value
}

/** Enough of an offending cookie value to recognise it, without pasting a session token into a log. */
private const val COOKIE_VALUE_PREVIEW = 40
