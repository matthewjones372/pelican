package dev.pelican

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * A file a caller uploaded, handed to the handler unread.
 *
 * Reading it is [stream]. Nothing here has held the bytes: the stream is a
 * window onto the request's own body, stopping at the part's boundary, so a
 * hundred-megabyte upload costs a hundred megabytes of *disk* wherever the
 * handler puts it and nothing at all here.
 *
 * Unlike `rawBody()`, this is not the backend's own stream type dressed up.
 * A raw body is the request's entity, which is a thing each backend already
 * has a name for; a part of a multipart envelope only exists because the
 * envelope was parsed, and the parsing is core's — so the stream is core's
 * too, and a handler that reads an upload reads it the same way on all three.
 *
 * The constructor is public because a *caller* has to build one too: the typed
 * test client and the generated client both send a file part, and they send it
 * by describing the same three things a server reads back off the wire.
 */
class UploadedFile(
    /** What the caller called it. Advisory, attacker-controlled, and often absent. */
    val filename: String?,
    /** The part's own `Content-Type`, if it declared one. */
    val contentType: String?,
    private val body: InputStream,
) {
    /** The part's bytes, unread. Consume it before the handler returns. */
    fun stream(): InputStream = body

    /**
     * The whole part, in memory. There for the cases where that is genuinely
     * what you want — a small text file, a test — and named so that choosing
     * it is visible in the handler rather than implied.
     */
    fun bytes(): ByteArray = body.use { it.readBytes() }

    fun text(charset: Charset = StandardCharsets.UTF_8): String = String(bytes(), charset)

    override fun toString() = "UploadedFile(filename=$filename, contentType=$contentType)"
}

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
 * The interpreters share this rather than each reaching for its own server's
 * multipart support: http4k-core has none, Pekko's is a stream of its own and
 * Ktor's is a suspending one, and three parsers would be three sets of
 * behaviour to reconcile — which part wins when a name repeats, what an absent
 * `filename` means, whether a text field is trimmed. One parser is one answer,
 * and `MultipartTest` is where that answer is written down.
 *
 * Text parts are read as they arrive, into [into], bounded in total by
 * [maxTextBytes]. The file part is not read at all: when the reader reaches it
 * the handler is given a stream positioned at its first byte, which is the
 * whole point.
 *
 * That is also the constraint worth stating plainly: **the file part has to be
 * the last part on the wire.** Reading stops there, so a text part sent after
 * it has not been seen and never will be — nothing here buffers a file in
 * order to go back for it. A text part that is still missing when the file
 * arrives is a 400 that says exactly this. An HTML form satisfies the rule by
 * putting its `<input type="file">` last.
 */
fun MultipartBody.decode(
    contentType: String?,
    input: InputStream,
    maxTextBytes: Long,
    into: MutableMap<ParamKey<*>, Any?>,
) {
    val boundary = multipartBoundary(contentType)
        ?: throw ApiException(
            400,
            "Expected a multipart/form-data body",
            "Content-Type was ${contentType ?: "absent"}, with no boundary to read the parts by",
        )

    val reader = MultipartReader(input, boundary)
    var budget = maxTextBytes
    var stoppedAtFile: String? = null

    while (stoppedAtFile == null) {
        val part = reader.next() ?: break
        // A part nobody described is skipped, not refused, for the same reason
        // an undescribed form field is: browsers send more than the form says.
        when (val declared = parts.firstOrNull { it.name == part.name }) {
            null -> Unit

            is TextPart<*> -> {
                val text = part.body.readAtMost(budget) { throw PayloadTooLarge(maxTextBytes) }
                budget -= text.size
                into[declared] = declared.codec.decode(declared.name, String(text, StandardCharsets.UTF_8))
            }

            is FilePart<*> -> {
                into[declared] = UploadedFile(part.filename, part.contentType, part.body)
                stoppedAtFile = part.name
            }
        }
    }

    for (part in parts) {
        if (into.containsKey(part)) continue
        val required = when (part) {
            is TextPart<*> -> part.required
            is FilePart<*> -> part.required
        }
        if (required) {
            throw ApiException(
                400,
                "Missing required part '${part.name}'",
                if (stoppedAtFile == null) null
                else "Nothing buffers an upload, so reading stopped at the file part " +
                    "'$stoppedAtFile'. Send '${part.name}' before it.",
            )
        }
        into[part] = (part as? TextPart<*>)?.default
    }
}

/** Reads up to [limit] bytes, calling [tooLarge] if there are more. */
private inline fun InputStream.readAtMost(limit: Long, tooLarge: () -> Nothing): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    var remaining = limit
    while (true) {
        val read = read(buffer, 0, buffer.size)
        if (read < 0) return out.toByteArray()
        if (read > remaining) tooLarge()
        remaining -= read
        out.write(buffer, 0, read)
    }
}

// ----------------------------------------------------------------- the reader

/** One part of the envelope, as it comes off the wire. */
internal class MultipartPartData(
    val name: String?,
    val filename: String?,
    val contentType: String?,
    val body: InputStream,
)

/**
 * Walks a `multipart/form-data` envelope, one part at a time.
 *
 * Strictly forward-only, and each part's body is a live window on the source —
 * which is what "not buffered" has to mean. Asking for the next part drains
 * whatever is left of the current one, so a part nobody wanted costs no memory
 * either.
 */
internal class MultipartReader(source: InputStream, boundary: String) {

    // The first boundary is the only one with no CRLF in front of it. Putting
    // one there makes a single delimiter serve every part, and costs two bytes
    // rather than a special case in the scanner.
    private val scanner = BoundaryScanner(
        SequenceInputStream(ByteArrayInputStream(CRLF), source),
        "\r\n--$boundary".toByteArray(StandardCharsets.ISO_8859_1),
    )

    /** Whatever precedes the first boundary. Ignored, per RFC 2046, but it has to be read. */
    private var current: PartStream? = PartStream()
    private var finished = false

    fun next(): MultipartPartData? {
        if (finished) return null
        current?.drain()
        current = null
        scanner.consumeDelimiter()

        val first = scanner.readByte()
        val second = scanner.readByte()
        when {
            first == DASH && second == DASH -> {
                finished = true
                return null
            }

            first == CR && second == LF -> Unit

            // RFC 2046 allows whitespace between a boundary and its line end.
            else -> scanner.readLine()
        }

        var name: String? = null
        var filename: String? = null
        var contentType: String? = null
        while (true) {
            val line = scanner.readLine()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val header = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when {
                header.equals("Content-Disposition", ignoreCase = true) -> {
                    name = headerParameter(value, "name")
                    filename = headerParameter(value, "filename")
                }

                header.equals("Content-Type", ignoreCase = true) -> contentType = value
            }
        }

        val body = PartStream()
        current = body
        return MultipartPartData(name, filename, contentType, body)
    }

    private inner class PartStream : InputStream() {
        private var ended = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (ended) return -1
            val read = scanner.read(b, off, len)
            if (read < 0) ended = true
            return read
        }

        fun drain() {
            val scratch = ByteArray(4096)
            while (read(scratch, 0, scratch.size) >= 0) Unit
        }
    }
}

/**
 * Hands over the bytes before the next delimiter, and nothing past it.
 *
 * The delimiter can straddle two reads from the source, so the last
 * `delimiter.size - 1` buffered bytes are held back until either the delimiter
 * is found or more arrives — a partial match at the end of the buffer is the
 * one thing a naive copy would get wrong, and it would get it wrong by
 * shipping the first bytes of a boundary as if they were content.
 */
private class BoundaryScanner(
    private val source: InputStream,
    private val delimiter: ByteArray,
) {
    private var buffer = ByteArray(8192)
    private var start = 0
    private var end = 0
    private var exhausted = false
    private var atDelimiter = false

    /** Buffers at least [n] bytes, or as many as the source has left. */
    private fun fill(n: Int): Int {
        if (end - start >= n) return end - start
        if (start > 0) {
            System.arraycopy(buffer, start, buffer, 0, end - start)
            end -= start
            start = 0
        }
        if (buffer.size < n) buffer = buffer.copyOf(maxOf(n, buffer.size * 2))
        while (end - start < n && !exhausted) {
            val read = source.read(buffer, end, buffer.size - end)
            if (read < 0) exhausted = true else end += read
        }
        return end - start
    }

    fun readByte(): Int = if (fill(1) < 1) -1 else buffer[start++].toInt() and 0xFF

    /** One CRLF-terminated line, as ASCII. Part headers are the only thing this reads. */
    fun readLine(): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = readByte()
            if (b < 0) throw malformed("the envelope ended inside a part header")
            if (b != CR) {
                out.write(b)
                continue
            }
            val next = readByte()
            if (next == LF) return out.toString(StandardCharsets.UTF_8)
            out.write(b)
            if (next < 0) throw malformed("the envelope ended inside a part header")
            out.write(next)
        }
    }

    fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (atDelimiter) return -1
        if (len == 0) return 0

        // Two bytes past the delimiter are needed as well as the delimiter
        // itself, because what follows is what decides whether this *is* one.
        val available = fill(delimiter.size + 2)
        val found = indexOfDelimiter()
        if (found == start) {
            atDelimiter = true
            return -1
        }

        val safe = when {
            found >= 0 -> found - start
            exhausted -> throw malformed("no closing boundary")
            else -> available - (delimiter.size + 2) + 1
        }

        val n = minOf(len, safe)
        System.arraycopy(buffer, start, dst, off, n)
        start += n
        return n
    }

    fun consumeDelimiter() {
        // Reaching the delimiter is what a part's stream reports as its end, so
        // by the time this is called the scan has already found it.
        check(atDelimiter) { "The reader is not at a boundary" }
        start += delimiter.size
        atDelimiter = false
    }

    /**
     * The next real delimiter, or -1.
     *
     * "Real" is the whole subtlety. A boundary of `b0undary` makes
     * `\r\n--b0undaryish` a *prefix* match and not a boundary at all, so what
     * follows the match is checked too: RFC 2046 allows only the line ending
     * that starts the next part, or the `--` that closes the envelope. Without
     * that check a part whose content happens to contain a longer boundary-like
     * line would be silently cut in half.
     */
    private fun indexOfDelimiter(): Int {
        val limit = end - delimiter.size
        var i = start
        while (i <= limit) {
            var j = 0
            while (j < delimiter.size && buffer[i + j] == delimiter[j]) j++
            if (j == delimiter.size && terminatedAt(i + delimiter.size)) return i
            i++
        }
        return -1
    }

    private fun terminatedAt(index: Int): Boolean {
        // Fewer than two bytes left and nothing more coming: there is nothing
        // this could be except the last delimiter of a truncated envelope, and
        // reading it as content would only turn one complaint into another.
        if (index + 2 > end) return exhausted
        val first = buffer[index].toInt() and 0xFF
        val second = buffer[index + 1].toInt() and 0xFF
        return (first == CR && second == LF) || (first == DASH && second == DASH)
    }
}

private fun malformed(detail: String) = ApiException(400, "Malformed multipart body", detail)

private const val CR = '\r'.code
private const val LF = '\n'.code
private const val DASH = '-'.code
private val CRLF = byteArrayOf(CR.toByte(), LF.toByte())

/**
 * One parameter of a structured header value — the `name` of a
 * `Content-Disposition`, the `boundary` of a `Content-Type`.
 *
 * Split by hand rather than by regex because a quoted value may contain the
 * `;` this is splitting on, and a regex that gets that right is longer than
 * the loop.
 */
internal fun headerParameter(header: String, parameter: String): String? {
    val segments = mutableListOf<String>()
    val sb = StringBuilder()
    var quoted = false
    for (c in header) {
        when {
            c == '"' -> { quoted = !quoted; sb.append(c) }
            c == ';' && !quoted -> { segments += sb.toString(); sb.setLength(0) }
            else -> sb.append(c)
        }
    }
    segments += sb.toString()

    for (segment in segments) {
        val equals = segment.indexOf('=')
        if (equals < 0) continue
        if (!segment.substring(0, equals).trim().equals(parameter, ignoreCase = true)) continue
        val value = segment.substring(equals + 1).trim()
        return if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value.substring(1, value.length - 1).replace("\\\"", "\"")
        } else {
            value
        }
    }
    return null
}
