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

/** Enough of a body to recognise it in a message, without pasting a page into an exception. */
private const val TAG_BODY_SHOWN = 120
