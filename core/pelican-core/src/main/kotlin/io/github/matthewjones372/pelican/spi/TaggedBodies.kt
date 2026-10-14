package io.github.matthewjones372.pelican.spi

import io.github.matthewjones372.pelican.ErrorOutput
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.parseJson

/**
 * A failure's rendered body, with its tag written in where it has one.
 *
 * Here rather than in each interpreter for the reason [failureNamedBy] is: three interpreters
 * deciding separately is three chances to answer a tagged failure with a body that does not say
 * which failure it is, and a client told to branch on the tag would then see none.
 *
 * The tag goes first, so the field a reader needs to dispatch on is the one it meets first, and a
 * body that already carries the field has it replaced rather than duplicated — a codec that writes
 * the discriminator itself stays correct instead of emitting the key twice.
 */
fun taggedBody(declared: ErrorOutput<*>, encoded: String): String {
    val tag = declared.tag ?: return encoded
    val parsed = parseJson(encoded)
    require(parsed is JsonObj) {
        "$declared is tagged \"$tag\", so its body carries the tag as a field, but its codec rendered " +
            "${encoded.take(TAG_BODY_SHOWN)}. Only a JSON object can hold a tag."
    }
    val fields = mapOf(declared.discriminator to JsonStr(tag)) +
        parsed.fields.filterKeys { it != declared.discriminator }
    return JsonObj(fields).render()
}

/**
 * The tag a failure's body carries under [field], or null where it carries none.
 *
 * The reading half of [taggedBody], here for the same reason: a client that guessed at the shape
 * would disagree with the server the day either changed. Null covers every way a body can fail to
 * say which failure it is — not JSON, not an object, the field absent, the field not a string —
 * because a caller has one thing to do about all of them, and it is not to decode a payload it
 * cannot be sure of.
 */
fun tagIn(encoded: String, field: String): String? {
    val parsed = try {
        parseJson(encoded)
    } catch (_: IllegalArgumentException) {
        // What [parseJson] refuses a body with, and the one thing it throws.
        return null
    }
    return ((parsed as? JsonObj)?.fields?.get(field) as? JsonStr)?.value
}

/** Enough of a body to recognise it in a message, without pasting a page into an exception. */
private const val TAG_BODY_SHOWN = 120
