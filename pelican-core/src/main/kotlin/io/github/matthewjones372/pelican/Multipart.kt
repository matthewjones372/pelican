package io.github.matthewjones372.pelican

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * A file a caller uploaded, handed over unread: [stream] is a window onto the
 * request's own body, stopping at the part's boundary.
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

    /** The whole part, in memory, named so that choosing it is visible. */
    fun bytes(): ByteArray = body.use { it.readBytes() }

    fun text(charset: Charset = StandardCharsets.UTF_8): String = String(bytes(), charset)

    override fun toString() = "UploadedFile(filename=$filename, contentType=$contentType)"
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
 * Walks a `multipart/form-data` envelope one part at a time, forward-only, each
 * part's body a live window on the source. Asking for the next drains the
 * current one.
 */
internal class MultipartReader(source: InputStream, boundary: String) {

    // The first boundary is the only one with no CRLF in front. Prepending one
    // lets a single delimiter serve every part.
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

    /** One part's headers, keyed in lower case since header names are case-insensitive. */
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
 * Hands over the bytes before the next delimiter and nothing past it.
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

        // Two bytes past it too, because what follows decides whether it is one.
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
        // A part's stream ends at the delimiter, so the scan has found it.
        check(atDelimiter) { "The reader is not at a boundary" }
        start += delimiter.size
        atDelimiter = false
    }

    /**
     * The next real delimiter, or -1. A boundary of `b0undary` makes
     * `\r\n--b0undaryish` a prefix match, so what follows is checked as well:
     * RFC 2046 allows only the next part's line ending or the closing `--`.
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
        // Fewer than two bytes and nothing coming: the last delimiter of a
        // truncated envelope, and reading it as content changes nothing.
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
 * One parameter of a structured header value. Split by hand because a quoted
 * value may contain the `;`, and a regex getting that right is longer.
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
 * Splits only on the semicolons that separate parameters: `filename="a;b.txt"`
 * is one segment, which is why this is not `split(';')`.
 */
private fun semicolonSegments(header: String): List<String> {
    // A semicolon with an even number of quotes before it is outside a value.
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
internal const val READ_BUFFER_BYTES = 4096

/**
 * How much of a refused envelope is still read so the 413 goes down a whole
 * connection. Same number and reasoning as the Pekko strict-body overrun.
 */
internal const val DRAIN_OVERRUN_BYTES: Long = 64L * 1024L

/** A Kotlin Byte is signed; this is how one becomes the 0..255 the format talks about. */
private const val BYTE_MASK = 0xFF
