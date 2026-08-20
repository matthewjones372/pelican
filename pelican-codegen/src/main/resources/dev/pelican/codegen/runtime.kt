// ------------------------------------------------------------------ runtime
//
// Fixed. Nothing here depends on a particular API — only on how Pelican frames
// its responses, which is the same for every endpoint.

/** A response with a status the endpoint never declared. */
class ApiCallFailed(
    val status: Int,
    val method: String,
    val path: String,
    val body: String,
) : RuntimeException("$method $path -> $status: ${body.take(500)}")

/**
 * A call that either succeeded or came back as one of the failures its endpoint
 * declared.
 *
 * The failure side is a sealed type generated per endpoint, so a `when` over it
 * is exhaustive: add a failure to the endpoint, regenerate, and the calls that
 * do not handle it stop compiling.
 */
sealed interface Outcome<out F, out T> {
    data class Ok<T>(val value: T) : Outcome<Nothing, T>
    data class Err<F>(val failure: F) : Outcome<F, Nothing>
}

/** The success value, or null if the call came back as a declared failure. */
fun <F, T> Outcome<F, T>.valueOrNull(): T? = (this as? Outcome.Ok)?.value

/** The success value, or [ApiCallFailed] carrying the failure that arrived instead. */
fun <F, T> Outcome<F, T>.orThrow(): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> throw IllegalStateException("Call failed: $failure")
}

/**
 * A response read as it arrives, rather than after it has all arrived.
 *
 * Iterating to the end closes the connection. Stopping early does not, so a
 * caller who might stop early should `use` it:
 *
 * ```
 * client.listOrders(1L, limit = 100).use { orders ->
 *     orders.first { it.status == OrderStatus.SHIPPED }
 * }
 * ```
 */
class Streamed<T> internal constructor(
    private val body: InputStream,
    private val elements: Sequence<T>,
) : Sequence<T>, AutoCloseable {

    private var iterated = false

    override fun iterator(): Iterator<T> {
        check(!iterated) { "A streamed response can only be read once" }
        iterated = true
        val underlying = elements.iterator()
        return object : Iterator<T> {
            override fun hasNext(): Boolean = underlying.hasNext().also { if (!it) close() }
            override fun next(): T = underlying.next()
        }
    }

    override fun close() = body.close()
}

/** Newline-delimited JSON: one document per line. */
internal fun ndjsonFrames(reader: BufferedReader): Sequence<String> =
    reader.lineSequence().filter { it.isNotBlank() }

/**
 * Server-sent events. A frame's payload may be split across several `data:`
 * lines, rejoined with newlines here — the same rule the server framed them by.
 */
internal fun sseFrames(reader: BufferedReader): Sequence<String> = sequence {
    val data = mutableListOf<String>()
    for (raw in reader.lineSequence()) {
        val line = raw.removeSuffix("\r")
        when {
            line.isEmpty() -> if (data.isNotEmpty()) {
                yield(data.joinToString("\n"))
                data.clear()
            }

            line.startsWith(":") -> Unit // comment, typically a keep-alive
            line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
        }
    }
    if (data.isNotEmpty()) yield(data.joinToString("\n"))
}

/**
 * A JSON array, element by element.
 *
 * Decoding the whole body at once would defeat the point of the server having
 * flushed the elements as it produced them, so this splits the array at
 * top-level commas — tracking string and escape state, which is the only way a
 * comma can be part of an element rather than between two — and hands each
 * element to the codec on its own.
 */
internal fun jsonArrayFrames(reader: Reader): Sequence<String> = sequence {
    var started = false
    var depth = 0
    var inString = false
    var escaped = false
    val element = StringBuilder()

    while (true) {
        val read = reader.read()
        if (read < 0) break
        val ch = read.toChar()

        if (!started) {
            when {
                ch == '[' -> started = true
                ch.isWhitespace() -> Unit
                else -> error("Expected a JSON array, found '$ch'")
            }
            continue
        }

        if (inString) {
            element.append(ch)
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
            continue
        }

        when {
            ch == '"' -> { inString = true; element.append(ch) }
            ch == '[' || ch == '{' -> { depth++; element.append(ch) }
            ch == '}' || (ch == ']' && depth > 0) -> { depth--; element.append(ch) }
            ch == ']' -> {
                if (element.isNotBlank()) yield(element.toString())
                return@sequence
            }

            ch == ',' && depth == 0 -> {
                if (element.isNotBlank()) yield(element.toString())
                element.setLength(0)
            }

            else -> element.append(ch)
        }
    }
    if (element.isNotBlank()) yield(element.toString())
}

/**
 * A `multipart/form-data` body, and the content type naming its boundary.
 *
 * The two travel together because a boundary that reached the body and not the
 * header would produce a request no server can read, and there is no reason for
 * a caller to be able to make that mistake.
 */
class MultipartContent internal constructor(
    internal val publisher: HttpRequest.BodyPublisher,
    internal val contentType: String,
)

/**
 * Builds the envelope without holding a file in memory.
 *
 * The parts are chained as streams and handed to `ofInputStream`, so an upload
 * is read from wherever it lives at the speed the socket drains — the same
 * promise the server makes when it hands a file part to a handler unread.
 *
 * Text parts are written first whatever order the endpoint declared them in,
 * because a server stops reading at the file part: nothing buffers an upload in
 * order to go back for a field that followed it.
 *
 * The boundary is random per call rather than fixed. Streaming means the
 * content cannot be scanned for a clash beforehand, so the answer is a
 * delimiter no content will contain by accident.
 */
internal fun multipart(
    fields: List<Pair<String, Any?>>,
    files: List<Pair<String, UploadedFile?>>,
): MultipartContent {
    val boundary = "PelicanBoundary" + UUID.randomUUID().toString().replace("-", "")
    val parts = mutableListOf<InputStream>()

    fields.forEach { (name, value) ->
        val text = plain(value) ?: return@forEach
        parts += ascii("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
        parts += ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8))
        parts += ascii("\r\n")
    }

    files.forEach { (name, file) ->
        if (file == null) return@forEach
        val disposition = StringBuilder("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"")
        file.filename?.let { disposition.append("; filename=\"").append(it).append('"') }
        disposition.append("\r\nContent-Type: ").append(file.contentType ?: "application/octet-stream")
        disposition.append("\r\n\r\n")
        parts += ascii(disposition.toString())
        parts += file.stream()
        parts += ascii("\r\n")
    }

    parts += ascii("--$boundary--\r\n")

    val body = SequenceInputStream(Collections.enumeration(parts))
    return MultipartContent(
        HttpRequest.BodyPublishers.ofInputStream { body },
        "multipart/form-data; boundary=$boundary",
    )
}

private fun ascii(text: String): InputStream =
    ByteArrayInputStream(text.toByteArray(StandardCharsets.ISO_8859_1))

/** A path, query or header value as the string it travels as. */
private fun plain(value: Any?): String? = when (value) {
    null -> null
    is Enum<*> -> value.name
    else -> value.toString()
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

/** Path segments are encoded more conservatively than query values. */
private fun segment(value: Any?): String = urlEncode(plain(value) ?: "").replace("+", "%20")
