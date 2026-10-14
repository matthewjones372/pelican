package io.github.matthewjones372.pelican

/**
 * One path segment, read once its percent-escapes have been resolved.
 */
internal sealed interface DecodedSegment {

    /** The text the segment stood for. */
    @JvmInline
    value class Ok(val text: String) : DecodedSegment

    /** A `%` that no two hex digits followed, which no caller could have meant. */
    data object Malformed : DecodedSegment
}

/**
 * A path segment's own escapes and nothing else: `+` is a plus.
 *
 * `URLDecoder` is `application/x-www-form-urlencoded`, where `+` means a space.
 * That form belongs to a query string and a form body; RFC 3986 gives `+` no
 * such meaning in a path, so `/tags/c++` used to reach a handler as `"c  "`.
 * Malformed input comes back as [DecodedSegment.Malformed] rather than as the
 * `IllegalArgumentException` `URLDecoder` throws, so a router can refuse the
 * request instead of an interpreter turning it into a 500.
 */
internal fun decodeSegment(raw: String): DecodedSegment {
    // Most segments have nothing in them to decode, and decoding allocates.
    if (raw.indexOf(ESCAPE) < 0) return DecodedSegment.Ok(raw)

    val source = raw.toByteArray(Charsets.UTF_8)
    val decoded = ByteArray(source.size)
    var written = 0
    var read = 0
    while (read < source.size) {
        val byte = source[read]
        if (byte != ESCAPE_BYTE) {
            decoded[written++] = byte
            read++
            continue
        }
        if (read + ESCAPE_LENGTH > source.size) return DecodedSegment.Malformed
        val high = hexDigit(source[read + 1])
        val low = hexDigit(source[read + 2])
        if (high < 0 || low < 0) return DecodedSegment.Malformed
        decoded[written++] = ((high shl HALF_BYTE) or low).toByte()
        read += ESCAPE_LENGTH
    }
    return DecodedSegment.Ok(String(decoded, 0, written, Charsets.UTF_8))
}

/** `Character.digit` answers -1 for anything that is not one, which is the refusal. */
private fun hexDigit(byte: Byte): Int = Character.digit(byte.toInt() and BYTE, HEX)

private const val ESCAPE = '%'
private const val ESCAPE_BYTE = '%'.code.toByte()

/** `%` and the two hex digits that have to follow it. */
private const val ESCAPE_LENGTH = 3
private const val HEX = 16
private const val HALF_BYTE = 4
private const val BYTE = 0xFF
