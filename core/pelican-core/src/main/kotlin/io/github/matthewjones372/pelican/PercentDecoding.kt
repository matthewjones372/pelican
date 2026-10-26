package io.github.matthewjones372.pelican

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

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

/** The detail of every refusal of escapes that are not UTF-8: fixed, so a 400 reads the same on every backend. */
internal const val NOT_UTF8: String = "The percent-encoded bytes are not UTF-8"

/**
 * Throws if `text[start, end)` would not decode strictly as `application/x-www-form-urlencoded` UTF-8, and allocates
 * nothing where it would. An escape that is not `%` and two hex digits is put to `URLDecoder`, whose
 * `IllegalArgumentException` is kept, since what it refuses has varied by JDK; that wins over bytes that are not
 * UTF-8, which throw [NOT_UTF8] rather than decoding to U+FFFD.
 */
internal fun checkFormEncoded(text: String, start: Int, end: Int) {
    var utf8 = 0
    var i = start
    while (i < end) {
        if (text[i] != ESCAPE) {
            // A literal character is never a continuation byte.
            if (utf8 != 0) utf8 = BROKEN
            i++
        } else if (i + ESCAPE_LENGTH <= end && isAsciiHex(text[i + 1]) && isAsciiHex(text[i + 2])) {
            utf8 = afterByte(utf8, (hexDigit(text[i + 1]) shl HALF_BYTE) or hexDigit(text[i + 2]))
            i += ESCAPE_LENGTH
        } else {
            URLDecoder.decode(text.substring(start, end), StandardCharsets.UTF_8)
            i++
        }
    }
    require(utf8 == 0) { NOT_UTF8 }
}

/**
 * A UTF-8 reading as one `Int`, so checking one allocates nothing: 0 where a character just ended, [BROKEN] for good
 * once one cannot, and otherwise the bytes still owed with the range the next has to fall in.
 */
private fun afterByte(state: Int, byte: Int): Int {
    val owed = state and OWED
    return when {
        state == BROKEN -> BROKEN
        owed == 0 -> leading(byte)
        byte in (state shr LOW_SHIFT and BYTE)..(state shr HIGH_SHIFT) -> owing(owed - 1)
        else -> BROKEN
    }
}

/** RFC 3629's table, with the first continuation narrowed to refuse overlong forms, surrogates and past U+10FFFF. */
private fun leading(lead: Int): Int = when {
    lead <= ASCII_MAX -> 0
    lead < TWO_BYTE_LEAD -> BROKEN
    lead < THREE_BYTE_LEAD -> owing(1)
    lead == THREE_BYTE_LEAD -> owing(2, low = NO_OVERLONG_THREE)
    lead == SURROGATE_LEAD -> owing(2, high = BELOW_SURROGATES)
    lead < FOUR_BYTE_LEAD -> owing(2)
    lead == FOUR_BYTE_LEAD -> owing(FOUR_BYTE_OWED, low = NO_OVERLONG_FOUR)
    lead < LAST_LEAD -> owing(FOUR_BYTE_OWED)
    lead == LAST_LEAD -> owing(FOUR_BYTE_OWED, high = BELOW_PAST_UNICODE)
    else -> BROKEN
}

private fun owing(owed: Int, low: Int = CONTINUATION_MIN, high: Int = CONTINUATION_MAX): Int =
    if (owed == 0) 0 else owed or (low shl LOW_SHIFT) or (high shl HIGH_SHIFT)

private fun isAsciiHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

private fun hexDigit(c: Char): Int = Character.digit(c, HEX)

private const val BROKEN = -1
private const val OWED = 0b11
private const val LOW_SHIFT = 8
private const val HIGH_SHIFT = 16
private const val ASCII_MAX = 0x7F
private const val CONTINUATION_MIN = 0x80
private const val CONTINUATION_MAX = 0xBF
private const val TWO_BYTE_LEAD = 0xC2
private const val THREE_BYTE_LEAD = 0xE0
private const val SURROGATE_LEAD = 0xED
private const val FOUR_BYTE_LEAD = 0xF0
private const val LAST_LEAD = 0xF4
private const val FOUR_BYTE_OWED = 3
private const val NO_OVERLONG_THREE = 0xA0
private const val BELOW_SURROGATES = 0x9F
private const val NO_OVERLONG_FOUR = 0x90
private const val BELOW_PAST_UNICODE = 0x8F

/** `Character.digit` answers -1 for anything that is not one, which is the refusal. */
private fun hexDigit(byte: Byte): Int = Character.digit(byte.toInt() and BYTE, HEX)

private const val ESCAPE = '%'
private const val ESCAPE_BYTE = '%'.code.toByte()

/** `%` and the two hex digits that have to follow it. */
private const val ESCAPE_LENGTH = 3
private const val HEX = 16
private const val HALF_BYTE = 4
private const val BYTE = 0xFF
