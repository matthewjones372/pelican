package io.github.matthewjones372.pelican.spi

import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.DRAIN_OVERRUN_BYTES
import io.github.matthewjones372.pelican.FilePart
import io.github.matthewjones372.pelican.MultipartBody
import io.github.matthewjones372.pelican.MultipartPart
import io.github.matthewjones372.pelican.MultipartReader
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.PayloadTooLarge
import io.github.matthewjones372.pelican.READ_BUFFER_BYTES
import io.github.matthewjones372.pelican.TextPart
import io.github.matthewjones372.pelican.UploadedFile
import io.github.matthewjones372.pelican.headerParameter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * The boundary a `multipart/form-data` content type names, or null when the
 * header is not one.
 */
fun multipartBoundary(contentType: String?): String? {
    if (contentType == null) return null
    if (!contentType.substringBefore(';').trim().equals("multipart/form-data", ignoreCase = true)) return null
    return headerParameter(contentType, "boundary")
}

/**
 * Reads a multipart request into the values its parts were declared as.
 *
 * One parser rather than each backend's own — http4k-core has none, Pekko's is
 * a stream, Ktor's suspends — so that "which part wins when a name repeats" has
 * one answer. `MultipartTest` is where that answer is written down.
 */
fun MultipartBody.decode(
    contentType: String?,
    input: InputStream,
    maxInMemoryBytes: Long,
    into: MutableMap<ParamKey<*>, Any?>,
) {
    val boundary = multipartBoundary(contentType)
        ?: throw ApiException(
            400,
            "Expected a multipart/form-data body",
            "Content-Type was ${contentType ?: "absent"}, with no boundary to read the parts by",
        )

    val reader = MultipartReader(input, boundary)
    var budget = maxInMemoryBytes
    var stoppedAtFile: String? = null

    while (stoppedAtFile == null) {
        val part = reader.next() ?: break
        // Skipped rather than refused: browsers send more than the form says.
        when (val declared = parts.firstOrNull { it.name == part.name }) {
            null -> Unit

            is TextPart<*> -> {
                val text = part.body.readAtMost(budget) {
                    refuse(input, declared.name, bound = null, budget = budget, max = maxInMemoryBytes)
                }
                budget -= text.size
                into[declared] = declared.codec.decode(declared.name, String(text, StandardCharsets.UTF_8))
            }

            is FilePart<*> -> {
                val bound = declared.bufferedBytes
                if (bound == null) {
                    into[declared] = UploadedFile(part.filename, part.contentType, part.body)
                    stoppedAtFile = part.name
                } else {
                    // A part may declare more than the request may spend, and
                    // the request's budget is the one already partly spent.
                    val bytes = part.body.readAtMost(minOf(bound, budget)) {
                        refuse(input, declared.name, bound, budget, maxInMemoryBytes)
                    }
                    budget -= bytes.size
                    into[declared] =
                        UploadedFile(part.filename, part.contentType, ByteArrayInputStream(bytes))
                }
            }
        }
    }

    fillMissingParts(parts, into, stoppedAtFile)
}

/**
 * The 413 for a part that ran over, raised after draining the rest — up to a
 * bound. Unread bytes are bytes the client is still writing, and answering
 * mid-upload gives it a broken pipe instead of the status.
 */
private fun refuse(input: InputStream, part: String, bound: Long?, budget: Long, max: Long): Nothing {
    var remaining = DRAIN_OVERRUN_BYTES
    val scratch = ByteArray(READ_BUFFER_BYTES)
    while (remaining > 0) {
        val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
        if (read < 0) break
        remaining -= read
    }

    if (bound != null && bound <= budget) {
        throw PayloadTooLarge(
            bound,
            "The part '$part' is larger than the $bound bytes its declaration allows it to hold. " +
                "Raise maxBytes on bufferedFile(\"$part\", ...), or send less.",
        )
    }
    throw PayloadTooLarge(
        max,
        "The parts of this request read into memory come to more than the $max bytes it may hold, " +
            "and '$part' is where that ran out. Raise maxBodyBytes in api { }, or send less.",
    )
}

/**
 * What the declaration expected and the request did not send: a required part
 * is a 400, an optional one takes its default. The detail names the streamed
 * part reading stopped at, which separates "forgot it" from "sent it too late".
 */
private fun fillMissingParts(
    parts: List<MultipartPart<*>>,
    into: MutableMap<ParamKey<*>, Any?>,
    stoppedAtFile: String?,
) {
    parts.filterNot { into.containsKey(it) }.forEach { part ->
        val required = when (part) {
            is TextPart<*> -> part.required
            is FilePart<*> -> part.required
        }
        if (required) {
            throw ApiException(
                400,
                "Missing required part '${part.name}'",
                if (stoppedAtFile == null) null
                else "Nothing holds a streamed upload, so reading stopped at the file part " +
                    "'$stoppedAtFile'. Send '${part.name}' before it.",
            )
        }
        into[part] = (part as? TextPart<*>)?.default
    }
}

/** Reads up to [limit] bytes, calling [tooLarge] if there are more. */
private inline fun InputStream.readAtMost(limit: Long, tooLarge: () -> Nothing): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    var remaining = limit
    while (true) {
        val read = read(buffer, 0, buffer.size)
        if (read < 0) return out.toByteArray()
        if (read > remaining) tooLarge()
        remaining -= read
        out.write(buffer, 0, read)
    }
}
