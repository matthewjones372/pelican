package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.ByteStreamHandle
import io.github.matthewjones372.pelican.MultipartBody
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.PayloadTooLarge
import io.github.matthewjones372.pelican.spi.RequestBodyCodecs
import io.github.matthewjones372.pelican.spi.decode
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Request
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * How far past [Api.maxBodyBytes] a body with no declared length is still read, so the 413 goes down a connection
 * that is still whole rather than one closed while the client is writing. Pekko's overrun, for the same reason.
 */
private const val DRAIN_OVERRUN_BYTES: Long = 64L * 1024L

private const val READ_CHUNK_BYTES = 8 * 1024

/** RFC 9110's status for a caller who did not finish sending in time. */
private const val REQUEST_TIMEOUT = 408

internal class JettyByteStream(val source: Content.Source) : ByteStreamHandle

/** The request body as Jetty's own source. Nothing has been read from it yet. */
fun ByteStreamHandle.toSource(): Content.Source = (this as JettyByteStream).source

/** The request body as chunks read as the sequence is iterated, blocking: for a handler on a virtual thread. */
fun ByteStreamHandle.toSequence(): Sequence<ByteArray> {
    val input = Content.Source.asInputStream(toSource())
    return generateSequence { input.readNBytes(READ_CHUNK_BYTES).takeIf { it.isNotEmpty() } }.constrainOnce()
}

/**
 * A body a codec sees whole, read on Jetty's demand callbacks so no thread waits for it, checked against the
 * limit and decoded. A declared oversize is refused before a byte is read.
 */
internal fun readStrictBody(request: Request, api: Api, codec: RequestBodyCodecs): CompletionStage<Any?> {
    if (request.length > api.maxBodyBytes) return CompletableFuture.failedStage(PayloadTooLarge(api.maxBodyBytes))

    val ceiling = (api.maxBodyBytes + DRAIN_OVERRUN_BYTES).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return Content.Source.asByteArrayAsync(request, ceiling)
        .orTimeout(api.strictBodyTimeoutMillis, TimeUnit.MILLISECONDS)
        .exceptionally { t ->
            throw when {
                t.causes().any { it is TimeoutException } -> ApiException(
                    REQUEST_TIMEOUT,
                    "Timed out reading the request body",
                    "The body did not arrive within the ${api.strictBodyTimeoutMillis}ms this service " +
                        "waits for one. Send it faster, or raise strictBodyTimeoutMillis in api { }.",
                )

                // Jetty's refusal of a body past the ceiling it was given; it has no type of its own.
                t.causes().any { it is IllegalStateException && it.message?.startsWith("Max size") == true } ->
                    PayloadTooLarge(api.maxBodyBytes)

                else -> t
            }
        }
        .thenApply { bytes ->
            // Against the limit itself, not the ceiling the read was given.
            if (bytes.size > api.maxBodyBytes) throw PayloadTooLarge(api.maxBodyBytes)
            codec.decode(request.headers.get(HttpHeader.CONTENT_TYPE), bytes)
        }
}

/** Core's multipart reader blocks on the body as it arrives, so it reads on a thread that may block. */
private val MULTIPART_READERS = Executors.newVirtualThreadPerTaskExecutor()

/**
 * An envelope parsed by core as it arrives, a streamed file part handed over unread. Exempt from the limit as on
 * Pekko: the parts held in memory are bounded by it, and the streamed one is never held.
 */
internal fun readMultipart(
    request: Request,
    api: Api,
    body: MultipartBody,
    into: MutableMap<ParamKey<*>, Any?>,
): CompletionStage<Unit> = CompletableFuture.supplyAsync(
    {
        body.decode(
            contentType = request.headers.get(HttpHeader.CONTENT_TYPE),
            input = Content.Source.asInputStream(request),
            maxInMemoryBytes = api.maxBodyBytes,
            into = into,
        )
    },
    MULTIPART_READERS,
)

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }
