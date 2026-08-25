// ------------------------------------------------------------------ runtime
//
// Fixed. Nothing here depends on a particular API — only on how Pelican frames
// its responses, which is the same for every endpoint.

/**
 * A response this client cannot turn into a value: a status the endpoint never
 * declared, or a body its codec could not read. [cause] is the codec's own
 * failure in the second case and null in the first.
 */
class ApiCallFailed(
    val status: Int,
    val method: String,
    val path: String,
    body: String,
    cause: Throwable? = null,
) : RuntimeException("$method $path -> $status: ${body.take(MESSAGE_BODY_CHARS)}", cause) {

    /**
     * What arrived, up to the cap. A proxy's error page is as long as the proxy
     * cares to make it, and this is held for as long as something holds the
     * failure.
     */
    val body: String =
        if (body.length <= MAX_BODY_CHARS) body
        else body.take(MAX_BODY_CHARS) + "… (truncated; ${body.length} characters in all)"
}

/** Eight KiB of it, which is enough of a gateway's HTML to recognise it by. */
private const val MAX_BODY_CHARS = 8 * 1024

/** Less again in the message, which is the part that reaches a log line. */
private const val MESSAGE_BODY_CHARS = 500

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
 * A response read whole.
 *
 * Every call but a streaming one needs its body as text, and needs it after it
 * has looked at the status: a declared failure decodes the body, and so does
 * the throw for a status nothing declared. Reading it once, here, is what stops
 * those two from being a first reader and an empty stream.
 */
class TextResponse internal constructor(
    private val response: ClientResponse,
    /** The whole body, decoded as UTF-8. */
    val body: String,
) {
    val status: Int get() = response.status

    /**
     * One header off the response, as the string it travelled as. Null when it
     * was not sent — which the declared failures below carry through, rather
     * than insisting on a header the server may have had nothing to say about.
     */
    fun header(name: String): String? = response.header(name)
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
    internal val body: ClientRequest.Body,
    internal val contentType: String,
)

/**
 * Builds the envelope without holding a file in memory.
 *
 * The parts are chained as streams and handed over as a streaming body, so an
 * upload is read from wherever it lives at the speed the transport drains it —
 * the same promise the server makes when it hands a file part to a handler
 * unread.
 *
 * The parts arrive here already in the order a server reads them — everything
 * it reads as it arrives, and then the streamed part it stops at — because that
 * order is a property of the description and the generator reads it off there.
 * Nothing goes back for a field that followed the streamed part.
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
        ClientRequest.Body.Streaming { body },
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

/**
 * How many times one query parameter or cookie appears on the wire: not at
 * all when it was left out, once when it carries a value, and once per element
 * when it was declared as a repeated list.
 */
private fun occurrences(name: String, value: Any?): List<String> = when (value) {
    null -> emptyList()
    is Collection<*> -> value.mapNotNull { plain(it) }.onEach { element(name, it) }
    else -> listOfNotNull(plain(value))
}

/**
 * A list that travels as one occurrence, joined by the separator its
 * declaration named. Null for an absent parameter and for an empty list
 * alike: neither has anything to say, and `tags=` is not a shorter way of
 * saying nothing.
 */
private fun joined(name: String, values: Collection<*>?, separator: String): String? =
    values?.mapNotNull { plain(it) }?.onEach { element(name, it) }
        ?.takeIf { it.isNotEmpty() }?.joinToString(separator)

/**
 * One element of a list, checked before it is written.
 *
 * A server reads an occurrence carrying nothing as no element at all — that is
 * what makes `?tags=` mean "a field nobody filled in" — so an empty element
 * would arrive as a list one shorter than the one that was passed, with
 * nothing on either side able to tell. Refused here, where the caller still
 * has the list in hand.
 */
private fun element(name: String, value: String) {
    require(value.isNotEmpty()) {
        "'$name' was given an element that carries nothing, and an occurrence carrying nothing is not " +
            "an element — it would not arrive. Leave it out of the list."
    }
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

/** Path segments are encoded more conservatively than query values. */
private fun segment(value: Any?): String = urlEncode(plain(value) ?: "").replace("+", "%20")
