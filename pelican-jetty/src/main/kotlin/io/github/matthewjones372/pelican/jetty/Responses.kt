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
import io.github.matthewjones372.pelican.NdjsonOutput
import io.github.matthewjones372.pelican.NegotiatedOutput
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.Output
import io.github.matthewjones372.pelican.SseOutput
import io.github.matthewjones372.pelican.TextOutput
import io.github.matthewjones372.pelican.spi.failureNamedBy
import io.github.matthewjones372.pelican.spi.renderError
import io.github.matthewjones372.pelican.spi.selectedFor
import io.github.matthewjones372.pelican.spi.successNamedBy
import io.github.matthewjones372.pelican.spi.taggedBody
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.util.BufferUtil
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.thread.Scheduler
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

private const val JSON = "application/json"
private const val TEXT = "text/plain; charset=UTF-8"
private const val NDJSON = "application/x-ndjson"
private const val EVENT_STREAM = "text/event-stream"

/** A response whose status and headers are decided before a byte of its body is written. */
internal sealed interface Reply

internal class Strict(
    val status: Int,
    val contentType: String?,
    val body: ByteArray,
    val headers: List<Pair<String, String>> = emptyList(),
) : Reply

/**
 * A body written a frame at a time as [elements] yield them, never held whole. [frames] renders the elements;
 * [keepAlive] fills a gap that long between frames with an SSE comment.
 */
internal class Streamed(
    val status: Int,
    val contentType: String,
    val elements: Sequence<*>,
    val headers: List<Pair<String, String>> = emptyList(),
    val keepAlive: Duration? = null,
    @Suppress("UNCHECKED_CAST")
    val frames: (Sequence<Any?>) -> Sequence<ByteArray> = { it as Sequence<ByteArray> },
) : Reply

internal fun Reply.withHeaders(extra: List<Pair<String, String>>): Reply = if (extra.isEmpty()) this else when (this) {
    is Strict -> Strict(status, contentType, body, headers + extra)
    is Streamed -> Streamed(status, contentType, elements, headers + extra, keepAlive, frames)
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
            STREAM_WRITERS.execute { writeFrames(response, callback) }
        }
    }
}

private val STREAM_WRITERS: Executor = Executors.newVirtualThreadPerTaskExecutor()

/**
 * One write per frame, each waited on before the next is pulled, so a slow reader holds the producer. A failed
 * write ends the iteration, and the elements' iterator is closed however it ends.
 */
private fun Streamed.writeFrames(response: Response, callback: Callback) {
    val upstream = elements.iterator()
    val writer = FrameWriter(response, keepAlive, Thread.currentThread())
    try {
        writer.commit()
        frames(upstream.asSequence()).filter { it.isNotEmpty() }.forEach(writer::write)
        writer.finish(callback)
    } catch (t: Throwable) {
        writer.stop()
        callback.failed(t)
    } finally {
        (upstream as? AutoCloseable)?.close()
    }
}

/**
 * Serialises the frames and the keep-alive comments: Jetty allows one pending write. A keep-alive that cannot be
 * written means the client has gone, so it interrupts [producer], which may be parked waiting for an element.
 */
private class FrameWriter(
    private val response: Response,
    private val keepAlive: Duration?,
    private val producer: Thread,
) {
    private val writing = Semaphore(1)
    private val scheduler = response.request.components.scheduler
    private var idle: Scheduler.Task? = null
    private var stopped = false

    /** Sends the status and headers before the first element is asked for, as Pekko does. */
    fun commit() = write(ByteArray(0))

    fun write(frame: ByteArray) {
        writing.acquire()
        try {
            cancelKeepAlive()
            val written = Callback.Completable()
            response.write(false, ByteBuffer.wrap(frame), written)
            written.get()
        } finally {
            writing.release()
        }
        scheduleKeepAlive()
    }

    fun finish(callback: Callback) {
        writing.acquire()
        stop()
        response.write(true, BufferUtil.EMPTY_BUFFER, callback)
    }

    @Synchronized
    fun stop() {
        stopped = true
        cancelKeepAlive()
    }

    @Synchronized
    private fun cancelKeepAlive() {
        idle?.cancel()
        idle = null
    }

    @Synchronized
    private fun scheduleKeepAlive() {
        if (keepAlive == null || stopped) return
        idle?.cancel()
        idle = scheduler.schedule(::fill, keepAlive.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

    /** On the scheduler. A frame being written means the stream is not idle, and it reschedules once written. */
    private fun fill() {
        if (!writing.tryAcquire()) return
        response.write(
            false,
            ByteBuffer.wrap(KEEP_ALIVE),
            Callback.from(
                {
                    writing.release()
                    scheduleKeepAlive()
                },
                {
                    writing.release()
                    stop()
                    producer.interrupt()
                },
            ),
        )
    }
}

private val KEEP_ALIVE = SseOutput.KEEP_ALIVE_FRAME.toByteArray()

/**
 * Frames documents as one JSON array, as Pekko's `EntityStreamingSupport.json()` does. The bracket travels with
 * the first element, so an empty stream is `[]`.
 */
private fun jsonArrayFrames(elements: Sequence<Any?>, codec: BodyCodec<Any?>): Sequence<ByteArray> = sequence {
    var seen = false
    for (element in elements) {
        yield(((if (seen) "," else "[") + codec.encodeToString(element)).toByteArray())
        seen = true
    }
    yield((if (seen) "]" else "[]").toByteArray())
}

/** Pekko's own answer to a path no endpoint describes, so the two backends cannot be told apart by it. */
internal val NOT_FOUND = Strict(404, TEXT, "The requested resource could not be found.".toByteArray())

/** Pekko's own answer to a verb no route takes, naming the ones that are, each once, in the order routes named them. */
internal fun methodNotAllowed(declared: List<String>): Strict {
    val names = declared.distinct().joinToString(", ")
    return Strict(
        405,
        TEXT,
        "HTTP method not allowed, supported methods: $names".toByteArray(),
        listOf("Allow" to names),
    )
}

/** Turns a described [Output] plus a handler's result into a response. */
internal fun buildResponse(out: Output<*>, value: Any?, codecs: EndpointCodecs, accept: List<String>): Reply {
    // Resolved when the handler was built, so a null is a bug in that resolution.
    fun payload(): BodyCodec<Any?> = checkNotNull(codecs.payloadFor(out)) { "No codec was resolved for $out" }

    return when (out) {
        is JsonOutput<*> -> Strict(out.status, JSON, payload().encodeToString(value).toByteArray())

        is TextOutput -> Strict(out.status, TEXT, (value as String).toByteArray())

        is MediaOutput<*> ->
            Strict(out.status, contentTypeOf(out.mediaType), payload().encodeToString(value).toByteArray())

        is EmptyOutput -> Strict(out.status, null, ByteArray(0))

        is ByteStreamOutput -> Streamed(out.status, contentTypeOf(out.mediaType), value as Sequence<*>)

        is NdjsonOutput<*> -> {
            @Suppress("UNCHECKED_CAST")
            val o = out as NdjsonOutput<Any?>
            val c = payload()
            Streamed(out.status, NDJSON, value as Sequence<*>, frames = { s -> s.map { o.frame(c, it).toByteArray() } })
        }

        is SseOutput<*> -> {
            @Suppress("UNCHECKED_CAST")
            val o = out as SseOutput<Any?>
            val c = payload()
            val prelude = listOfNotNull(o.prelude()?.toByteArray())
            Streamed(
                out.status,
                EVENT_STREAM,
                value as Sequence<*>,
                keepAlive = o.keepAlive,
                frames = { s -> prelude.asSequence() + s.map { o.frame(c, it).toByteArray() } },
            )
        }

        is JsonArrayOutput<*> -> {
            val c = payload()
            Streamed(out.status, JSON, value as Sequence<*>, frames = { s -> jsonArrayFrames(s, c) })
        }

        is DeclaredResponses<*, *> -> when (val outcome = value as Outcome<*, *>) {
            is Outcome.Ok<*> -> buildResponse(out.successNamedBy(outcome), outcome.value, codecs, accept)
                .withHeaders(outcome.headers)

            is Outcome.Err<*> -> failureResponse(out.failureNamedBy(outcome), outcome, codecs)
        }

        // Which rendering goes out is core's answer, from the same scoring the 406 was decided with.
        is NegotiatedOutput<*> -> buildResponse(out.selectedFor(accept), value, codecs, accept)
    }
}

/** Its status from the declaration rather than the payload's type, so two failures sharing a type stay distinct. */
private fun failureResponse(declared: ErrorOutput<*>, err: Outcome.Err<*>, codecs: EndpointCodecs): Reply {
    val codec = checkNotNull(codecs.alternatives[declared]) { "No codec was resolved for $declared" }
    val body = taggedBody(declared, codec.encodeToString(err.error)).toByteArray()
    val contentType = if (declared.mediaType == JSON_MEDIA_TYPE) JSON else contentTypeOf(declared.mediaType)
    return Strict(declared.status, contentType, body, err.headers)
}

/** As Pekko writes a declared media type: its parameters dropped, and a text type's fixed UTF-8 left implied. */
private fun contentTypeOf(mediaType: String): String = mediaType.substringBefore(';').trim()

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
