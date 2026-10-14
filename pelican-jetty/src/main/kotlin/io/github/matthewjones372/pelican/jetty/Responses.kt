package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.BodyCodec
import io.github.matthewjones372.pelican.ByteStreamOutput
import io.github.matthewjones372.pelican.DeclaredResponses
import io.github.matthewjones372.pelican.EmptyOutput
import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.ErrorOutput
import io.github.matthewjones372.pelican.JSON_MEDIA_TYPE
import io.github.matthewjones372.pelican.JsonArrayOutput
import io.github.matthewjones372.pelican.JsonOutput
import io.github.matthewjones372.pelican.MediaOutput
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.NdjsonOutput
import io.github.matthewjones372.pelican.NegotiatedOutput
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.Output
import io.github.matthewjones372.pelican.SseOutput
import io.github.matthewjones372.pelican.TextOutput
import io.github.matthewjones372.pelican.spi.failureNamedBy
import io.github.matthewjones372.pelican.spi.renderError
import io.github.matthewjones372.pelican.spi.successNamedBy
import io.github.matthewjones372.pelican.spi.taggedBody
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.BufferUtil
import org.eclipse.jetty.util.Callback
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private const val JSON = "application/json"
private const val TEXT = "text/plain; charset=UTF-8"

/** A response whose status and headers are decided before a byte of its body is written. */
internal sealed interface Reply

internal class Strict(
    val status: Int,
    val contentType: String?,
    val body: ByteArray,
    val headers: List<Pair<String, String>> = emptyList(),
) : Reply

/** A body written a chunk at a time as [chunks] yields them, never held whole. */
internal class Streamed(
    val status: Int,
    val contentType: String,
    val chunks: Sequence<ByteArray>,
    val headers: List<Pair<String, String>> = emptyList(),
) : Reply

internal fun Reply.withHeaders(extra: List<Pair<String, String>>): Reply = when (this) {
    is Strict -> if (extra.isEmpty()) this else Strict(status, contentType, body, headers + extra)
    is Streamed -> if (extra.isEmpty()) this else Streamed(status, contentType, chunks, headers + extra)
}

internal fun Reply.writeTo(response: Response, callback: Callback) {
    when (this) {
        is Strict -> {
            response.status = status
            headers.forEach { (name, value) -> response.headers.add(name, value) }
            contentType?.let { response.headers.put(HttpHeader.CONTENT_TYPE, it) }
            if (body.isNotEmpty()) response.headers.put(HttpHeader.CONTENT_LENGTH, body.size.toLong())
            response.write(true, ByteBuffer.wrap(body), callback)
        }

        is Streamed -> {
            response.status = status
            headers.forEach { (name, value) -> response.headers.add(name, value) }
            response.headers.put(HttpHeader.CONTENT_TYPE, contentType)
            // Iterating may block, on the request body for one, so never on a Jetty thread.
            STREAM_WRITERS.execute { writeChunks(response, callback) }
        }
    }
}

private val STREAM_WRITERS: Executor = Executors.newVirtualThreadPerTaskExecutor()

/** One write per chunk, each waited on before the next is pulled, so a slow reader holds the producer. */
private fun Streamed.writeChunks(response: Response, callback: Callback) {
    try {
        chunks.filter { it.isNotEmpty() }.forEach { chunk ->
            val written = Callback.Completable()
            response.write(false, ByteBuffer.wrap(chunk), written)
            written.get()
        }
        response.write(true, BufferUtil.EMPTY_BUFFER, callback)
    } catch (t: Throwable) {
        callback.failed(t)
    }
}

/** Pekko's own answer to a path no endpoint describes, so the two backends cannot be told apart by it. */
internal val NOT_FOUND = Strict(404, TEXT, "The requested resource could not be found.".toByteArray())

/** Pekko's own answer to a verb no endpoint declares, naming the ones that are. */
internal fun methodNotAllowed(declared: List<Method>): Strict {
    val names = declared.joinToString(", ") { it.name }
    return Strict(
        405,
        TEXT,
        "HTTP method not allowed, supported methods: $names".toByteArray(),
        listOf("Allow" to names),
    )
}

/** Turns a described [Output] plus a handler's result into a response. */
internal fun buildResponse(out: Output<*>, value: Any?, codecs: EndpointCodecs): Reply {
    // Resolved when the handler was built, so a null is a bug in that resolution.
    fun payload(): BodyCodec<Any?> = checkNotNull(codecs.payloadFor(out)) { "No codec was resolved for $out" }

    return when (out) {
        is JsonOutput<*> -> Strict(out.status, JSON, payload().encodeToString(value).toByteArray())

        is TextOutput -> Strict(out.status, TEXT, (value as String).toByteArray())

        is MediaOutput<*> ->
            Strict(out.status, contentTypeOf(out.mediaType), payload().encodeToString(value).toByteArray())

        is EmptyOutput -> Strict(out.status, null, ByteArray(0))

        is ByteStreamOutput -> {
            @Suppress("UNCHECKED_CAST")
            Streamed(out.status, contentTypeOf(out.mediaType), value as Sequence<ByteArray>)
        }

        is DeclaredResponses<*, *> -> when (val outcome = value as Outcome<*, *>) {
            is Outcome.Ok<*> -> buildResponse(out.successNamedBy(outcome), outcome.value, codecs)
                .withHeaders(outcome.headers)

            is Outcome.Err<*> -> failureResponse(out.failureNamedBy(outcome), outcome, codecs)
        }

        is NegotiatedOutput<*>,
        is NdjsonOutput<*>, is SseOutput<*>, is JsonArrayOutput<*>,
        -> throw UnsupportedOperationException("Not yet served on Jetty: $out")
    }
}

/** Its status from the declaration rather than the payload's type, so two failures sharing a type stay distinct. */
private fun failureResponse(declared: ErrorOutput<*>, err: Outcome.Err<*>, codecs: EndpointCodecs): Reply {
    val codec = checkNotNull(codecs.alternatives[declared]) { "No codec was resolved for $declared" }
    val body = taggedBody(declared, codec.encodeToString(err.error)).toByteArray()
    val contentType = if (declared.mediaType == JSON_MEDIA_TYPE) JSON else contentTypeOf(declared.mediaType)
    return Strict(declared.status, contentType, body, err.headers)
}

/** As Pekko writes it: a text type carries its charset, anything else is sent as declared. */
private fun contentTypeOf(mediaType: String): String =
    if (mediaType.startsWith("text/") && !mediaType.contains("charset")) "$mediaType; charset=UTF-8" else mediaType

private val log: Logger = LoggerFactory.getLogger("io.github.matthewjones372.pelican.jetty")

/** Rendered through core's own tree rather than the configured codec, which may be what just failed. */
internal fun errorResponse(raw: Throwable, api: Api, endpoint: Endpoint<*, *>? = null): Reply {
    val rendered = renderError(raw, api, endpoint)

    rendered.unexpected?.let { failure ->
        val reference = checkNotNull(rendered.reference) { "an unexpected failure with no reference" }
        val hook = api.onServerError
        if (hook != null) hook(reference, endpoint, failure)
        else log.error("Unhandled failure in {} [ref {}]", endpoint ?: "?", reference, failure)
    }

    return Strict(
        rendered.error.status,
        contentTypeOf(rendered.body.mediaType),
        rendered.body.bytes,
        rendered.headers,
    )
}
