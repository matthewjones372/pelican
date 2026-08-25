package io.github.matthewjones372.pelican

/**
 * A header the endpoint promises to send back, declared once as a value and
 * reused by the document and by the handler that sets it. Setting one the
 * endpoint never declared throws, so the document and the wire cannot disagree.
 *
 * The same value declares a header on a *declared failure*, where it belongs to
 * that response alone; see [ErrorOutput.invoke].
 */
class ResponseHeader<T> @PublishedApi internal constructor(
    val name: String,
    val codec: PlainCodec<*>,
    val description: String? = null,
    /** False for a header sent only sometimes — `Retry-After`, a paging cursor. */
    val required: Boolean = true,
) {
    init {
        require(name.isNotBlank()) { "A response header needs a name" }
        require(name.lowercase() !in RESERVED) {
            "$name is set by the server from the response itself, not declared as a header. " +
                "Every backend either drops it or sends a second, conflicting copy: " +
                "Pekko logs it and renders nothing. Content type comes from the output " +
                "(json, text, bytes(mediaType)); length and framing come from the body."
        }
    }

    override fun toString() = "responseHeader:$name"
}

/**
 * Headers no description may claim, because the server underneath owns them.
 * Exactly the ones Pekko's renderer drops from a `RawHeader` with only a log
 * line; the other two send a conflicting duplicate instead.
 */
private val RESERVED = setOf(
    "content-type",
    "content-length",
    "transfer-encoding",
    "connection",
    "date",
    "server",
)

inline fun <reified T : Any> responseHeader(
    name: String,
    description: String? = null,
): ResponseHeader<T> = ResponseHeader(name, plainCodecFor<T>(), description)

fun <T : Any> responseHeader(
    name: String,
    codec: PlainCodec<T>,
    description: String? = null,
): ResponseHeader<T> = ResponseHeader(name, codec, description)

/**
 * Marks the header as one the endpoint may leave off. It is still declared, so
 * it may still be set; the document simply stops promising it is always there.
 */
fun <T : Any> ResponseHeader<T>.optional(): ResponseHeader<T> =
    ResponseHeader(name, codec, description, required = false)
