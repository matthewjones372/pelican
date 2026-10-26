package io.github.matthewjones372.pelican.gannet

import io.github.matthewjones372.gannet.BodyTooLargeException
import io.github.matthewjones372.gannet.HttpRequest
import io.github.matthewjones372.gannet.RequestBody
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiException
import io.github.matthewjones372.pelican.ByteStreamHandle
import io.github.matthewjones372.pelican.MultipartBody
import io.github.matthewjones372.pelican.ParamKey
import io.github.matthewjones372.pelican.PayloadTooLarge
import io.github.matthewjones372.pelican.spi.RequestBodyCodecs
import io.github.matthewjones372.pelican.spi.decode
import java.io.IOException

private const val READ_CHUNK_BYTES = 8 * 1024

/** RFC 9110's status for a caller who did not finish sending in time. */
private const val REQUEST_TIMEOUT = 408

internal class GannetByteStream(val body: RequestBody) : ByteStreamHandle

/** The request body as Gannet's own handle. Nothing has been read from it yet. */
fun ByteStreamHandle.toRequestBody(): RequestBody = (this as GannetByteStream).body

/** The request body as chunks read as the sequence is iterated, blocking: for a handler on a virtual thread. */
fun ByteStreamHandle.toSequence(): Sequence<ByteArray> {
    val input = toRequestBody().inputStream()
    return generateSequence { input.readNBytes(READ_CHUNK_BYTES).takeIf { it.isNotEmpty() } }.constrainOnce()
}

/**
 * A body a codec sees whole, checked against the limit and decoded. A declared oversize is refused before a byte is
 * read; one with no declared length is read to the limit and refused, and Gannet drains what is left of it so
 * the 413 goes down a connection that is still whole. On the carrier the read parks only the connection's fiber.
 * A read that outlives [Api.strictBodyTimeoutMillis] is failed by the sweeper, which wakes it.
 */
internal fun readStrictBody(request: HttpRequest, api: Api, codec: RequestBodyCodecs): Any? =
    codec.decode(request.headers[CONTENT_TYPE], readStrictBytes(request, api))

/** [readStrictBody]'s read, undecoded. */
internal fun readStrictBytes(request: HttpRequest, api: Api): ByteArray {
    val body = request.body
    val declared = body.length
    if (declared != null && declared > api.maxBodyBytes) throw PayloadTooLarge(api.maxBodyBytes)

    val deadline = DEADLINES.arm(body, api.strictBodyTimeoutMillis)
    return try {
        body.readAll(api.maxBodyBytes)
    } catch (e: BodyTooLargeException) {
        throw PayloadTooLarge(api.maxBodyBytes).apply { addSuppressed(e) }
    } catch (e: IOException) {
        throw if (e === deadline.settle()) {
            ApiException(
                REQUEST_TIMEOUT,
                "Timed out reading the request body",
                "The body did not arrive within the ${api.strictBodyTimeoutMillis}ms this service " +
                    "waits for one. Send it faster, or raise strictBodyTimeoutMillis in api { }.",
                cause = e,
            )
        } else {
            ApiException(BAD_REQUEST, "Malformed request body", e.message, cause = e)
        }
    } finally {
        deadline.settle()
    }
}

/**
 * An envelope parsed by core as it arrives, a streamed file part handed over unread. Exempt from the limit as on
 * Pekko: the parts held in memory are bounded by it, and the streamed one is never held. Blocking, so never called
 * on the carrier.
 */
internal fun readMultipart(request: HttpRequest, api: Api, body: MultipartBody, into: MutableMap<ParamKey<*>, Any?>) {
    body.decode(
        contentType = request.headers[CONTENT_TYPE],
        input = TimedInput(request.body, READ_TIMEOUT_MILLIS),
        maxInMemoryBytes = api.maxBodyBytes,
        into = into,
    )
}

internal const val CONTENT_TYPE = "Content-Type"
private const val BAD_REQUEST = 400
