package io.github.matthewjones372.pelican

/**
 * Reads and writes the `Cookie` request header.
 *
 * All three backends have a cookie API of their own, and using each would have
 * been less code here and more behaviour to reconcile: one of them unquotes,
 * another does not; one splits on `,` as well as `;`. A cookie parameter is
 * supposed to decode to the same value whichever server is underneath, so the
 * splitting lives here and the backends only hand over the header.
 *
 * Values travel exactly as written. RFC 6265 already excludes `;`, `,`, a
 * space and a control character from a cookie value, so there is nothing to
 * escape — and percent-decoding by default would silently corrupt a value that
 * legitimately contains a `%`. A codec that produces a character a cookie
 * cannot carry is a codec that cannot be a cookie, and [render] says so rather
 * than writing a header a browser will mangle.
 */
object Cookies {

    /**
     * The cookies in these header values, first spelling of a name winning.
     *
     * A request may carry more than one `Cookie` header, and RFC 6265 orders
     * them most-specific first — so an earlier value is the more specific one
     * and a later duplicate is the one to drop.
     */
    fun parse(headers: List<String>): Map<String, String> =
        parseAll(headers).mapValues { (_, values) -> values.first() }

    fun parse(header: String?): Map<String, String> = parse(listOfNotNull(header))

    /**
     * The same, keeping every spelling of a name rather than the first.
     *
     * A `Cookie` header may repeat a name, and for a cookie declared as a list
     * that repetition *is* the list — it is the only encoding RFC 6265 leaves
     * available, since the comma a delimited style would need is not a
     * character a cookie value may carry. [parse] stays the reading for
     * everything else, and is defined in terms of this so the two cannot
     * disagree about what a duplicate means.
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
     * A quoted cookie value, unquoted. Servers that write `name="value"` are
     * common enough that reading the quotes back as part of the value would
     * fail a decode for a reason nobody could see in the header.
     */
    private fun unquote(value: String): String =
        if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value.substring(1, value.length - 1)
        else value
}

/** Enough of an offending cookie value to recognise it, without pasting a session token into a log. */
private const val COOKIE_VALUE_PREVIEW = 40
