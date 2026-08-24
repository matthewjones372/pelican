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
 * Text parts and [bufferedFile] parts are read as they arrive, into [into]. A
 * streamed file part is not read at all: when the reader reaches it the handler
 * is given a stream positioned at its first byte, which is the whole point.
 *
 * That is also the constraint worth stating plainly: **the streamed part has to
 * be the last part on the wire.** Reading stops there, so anything sent after
 * it has not been seen and never will be — nothing here goes back for it. A
 * part that is still missing when the streamed file arrives is a 400 that says
 * exactly this. An HTML form satisfies the rule by putting its last
 * `<input type="file">` last.
 *
 * Everything held in memory shares one budget, [maxInMemoryBytes], on top of
 * whatever bound each buffered part declared for itself. Two bounds rather than
 * one because they answer different questions: the per-part bound is what the
 * *description* promises about one field, and the budget is what the *server*
 * will spend on one request however many fields it declared.
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
        // A part nobody described is skipped, not refused, for the same reason
        // an undescribed form field is: browsers send more than the form says.
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
                    // The smaller of the two: a part may declare more than the
                    // request as a whole is allowed to spend, and the request's
                    // budget is the one that has already been partly spent.
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
 * The 413 for a part that ran over, raised only after the rest of the envelope
 * has been read — up to a point.
 *
 * The draining is what makes the refusal arrive. Bytes left unread on the
 * connection are bytes the client is still writing, and a server that answers
 * and closes mid-upload gives it a broken pipe instead of the status that
 * explains itself; the Pekko interpreter reads a strict body slightly past its
 * limit for exactly this reason. Here the overrun is bounded and then given up
 * on: a caller that keeps pushing gets the connection cut, because nothing can
 * be promised to a sender that will not stop.
 *
 * Which of the two bounds is named matters more than it looks. A caller told
 * that a part exceeded seven bytes, because six of the request's budget had
 * already gone on a caption, would go looking for a seven that nobody wrote
 * down. So the message names the number that *was* written down: the part's own
 * [bound] where that is what stopped it, and [max] where the request as a whole
 * is what ran out.
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
            "and '$part' is where that ran out. Raise Api(maxBodyBytes = ...), or send less.",
    )
}

/**
 * What the declaration expected and the request did not send: a required part
 * is a 400, an optional one takes its default.
 *
 * The detail names the streamed part reading stopped at, when there was one.
 * That is the whole difference between "you forgot a field" and "you sent it
 * after the upload, and nothing goes back for it".
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

        val headers = partHeaders()
        val disposition = headers["content-disposition"]
        val name = disposition?.let { headerParameter(it, "name") }
        val filename = disposition?.let { headerParameter(it, "filename") }
        val contentType = headers["content-type"]

        val body = PartStream()
        current = body
        return MultipartPartData(name, filename, contentType, body)
    }

    /**
     * The headers of one part, up to the blank line that ends them. Keyed in
     * lower case: header names are case-insensitive, and folding them once
     * here is cheaper than remembering to compare them that way at each use.
     */
    private fun partHeaders(): Map<String, String> =
        generateSequence { scanner.readLine().takeIf { it.isNotEmpty() } }
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0) null
                else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }
            .toMap()

    private inner class PartStream : InputStream() {
        private var ended = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (ended) return -1
            val read = scanner.read(b, off, len)
            if (read < 0) ended = true
            return read
        }

        fun drain() {
            val scratch = ByteArray(READ_BUFFER_BYTES)
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

    fun readByte(): Int = if (fill(1) < 1) -1 else buffer[start++].toInt() and BYTE_MASK

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
        val first = buffer[index].toInt() and BYTE_MASK
        val second = buffer[index + 1].toInt() and BYTE_MASK
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
internal fun headerParameter(header: String, parameter: String): String? =
    semicolonSegments(header).firstNotNullOfOrNull { segment ->
        val equals = segment.indexOf('=')
        val name = if (equals < 0) null else segment.substring(0, equals).trim()
        if (name != null && name.equals(parameter, ignoreCase = true)) {
            unquoteParameter(segment.substring(equals + 1).trim())
        } else {
            null
        }
    }

/**
 * Splits on the semicolons that separate header parameters, and only those:
 * a semicolon inside a quoted value is part of the value. `filename="a;b.txt"`
 * is one segment, which is the whole reason this is not `split(';')`.
 */
private fun semicolonSegments(header: String): List<String> {
    // How many quotes precede each index. A semicolon with an even number in
    // front of it is outside a quoted value, and so is a real separator.
    val quotesBefore = header.runningFold(0) { seen, c -> if (c == '"') seen + 1 else seen }
    val cuts = header.indices.filter { i -> header[i] == ';' && quotesBefore[i] % 2 == 0 }

    return (listOf(-1) + cuts + header.length).zipWithNext { from, to -> header.substring(from + 1, to) }
}

/** `"a b"` is the value `a b`; an escaped quote inside it is a quote. */
private fun unquoteParameter(value: String): String =
    if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
        value.substring(1, value.length - 1).replace("\\\"", "\"")
    } else {
        value
    }

/** One page. Big enough that the syscall is not the cost, small enough to hold several. */
private const val READ_BUFFER_BYTES = 4096

/**
 * How much of a refused envelope is still read so that the 413 can be written
 * down a connection that is still whole. Sixty-four kilobytes, the same number
 * and the same reasoning as the Pekko interpreter's strict-body overrun.
 */
private const val DRAIN_OVERRUN_BYTES: Long = 64L * 1024L

/** A Kotlin Byte is signed; this is how one becomes the 0..255 the format talks about. */
private const val BYTE_MASK = 0xFF
