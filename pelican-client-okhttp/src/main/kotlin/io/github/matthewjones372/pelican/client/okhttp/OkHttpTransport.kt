package io.github.matthewjones372.pelican.client.okhttp

import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.ClientTransport
import io.github.matthewjones372.pelican.Method
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A [ClientTransport] over OkHttp, for a caller who would rather send through
 * the client their application already configures than acquire a second one —
 * and the one an Android app takes, where `java.net.http` does not exist.
 *
 * Declared as a service provider, so adding this module is enough to choose it
 * — but see [ClientTransport.default], which refuses to guess when a second
 * adapter is also on the classpath.
 */
class OkHttpTransport @JvmOverloads constructor(
    /**
     * The client to send on. An application that has configured one hands it
     * over and keeps its interceptors, its cache, its connection pool and its
     * timeouts; null takes the one this module keeps for callers who have none.
     */
    client: OkHttpClient? = null,
) : ClientTransport {

    /**
     * Resolved on the first request rather than in the constructor, because
     * [ClientTransport.default] instantiates every provider it finds in order
     * to count them: a transport that built a client while being counted would
     * leave a connection pool behind the error that followed.
     */
    private val running: OkHttpClient by lazy { client ?: shared }

    /**
     * `enqueue` rather than `execute`, since the answer is a [CompletionStage]
     * either way and the blocking form would only be this one holding a thread
     * that the dispatcher already has.
     *
     * The stage completes when the response head arrives, with the body still
     * on the socket for whoever reads it. Which makes cancellation
     * two-directional and both directions necessary: a stage the caller cancels
     * cancels the call, and a call that fails before the head arrives fails the
     * stage. Neither can win twice, since a `CompletableFuture` completes once.
     */
    override fun send(request: ClientRequest): CompletionStage<ClientResponse> {
        val answer = CompletableFuture<ClientResponse>()
        val call = running.newCall(okHttpRequest(request))

        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    answer.completeExceptionally(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    // False where the deadline or a cancellation got there
                    // first. Closing releases the connection rather than
                    // leaving it held for a body nobody will read.
                    if (!answer.complete(clientResponse(response))) response.close()
                }
            },
        )

        val deadline = request.timeout?.let { limit -> deadline(request, limit, answer) }

        answer.whenComplete { _, failure ->
            deadline?.cancel(false)
            // Cancelled, expired, or failed before the head arrived. Either way
            // the call has nobody left to answer, and a call nobody is waiting
            // on must not keep holding a connection.
            if (failure != null) call.cancel()
        }

        return answer
    }

    /**
     * The per-request [Duration], applied to the arrival of the response head
     * and not to the reading of its body.
     *
     * OkHttp's own `callTimeout` is the whole exchange — the reading of a
     * streamed body included — so it is not what a [ClientRequest.timeout]
     * means here: it would cut off every `sse` response that stayed open longer
     * than the deadline the client was built with, and the JDK and Pekko
     * adapters both bound the head alone. Nothing else in OkHttp bounds the
     * head, its read and write timeouts being limits on a single socket
     * operation rather than on the exchange, so the deadline is imposed on the
     * stage instead.
     *
     * `InterruptedIOException` because that is what OkHttp raises when its own
     * call timeout fires, so a caller catching a timeout by type catches it
     * whichever of the two imposed it — and because it is an `IOException`,
     * which is what [io.github.matthewjones372.pelican.RetryPolicy] retries by
     * default, exactly as a JDK `HttpTimeoutException` is.
     */
    private fun deadline(
        request: ClientRequest,
        limit: Duration,
        answer: CompletableFuture<ClientResponse>,
    ): CompletableFuture<*> = CompletableFuture.runAsync(
        { answer.completeExceptionally(InterruptedIOException("$request timed out after $limit")) },
        CompletableFuture.delayedExecutor(limit.toMillis(), TimeUnit.MILLISECONDS),
    )

    private fun okHttpRequest(request: ClientRequest): Request {
        val builder = Request.Builder().url(request.url)

        // OkHttp carries these two on the body rather than in the header list,
        // and rewrites a header of either name from what the body reports. They
        // are read off here and put where OkHttp keeps them.
        request.headers
            .filterNot { (name, _) -> name.equals(CONTENT_TYPE, true) || name.equals(CONTENT_LENGTH, true) }
            // `addHeader` adds rather than replaces, which is what a request
            // carrying one name twice needs.
            .forEach { (name, value) -> builder.addHeader(name, value) }

        return builder.method(request.method.name, body(request)).build()
    }

    private fun body(request: ClientRequest): RequestBody? {
        val declared = request.header(CONTENT_TYPE)?.toMediaTypeOrNull()

        return when (val body = request.body) {
            // OkHttp refuses a body on GET and HEAD and insists on one for
            // POST, PUT and PATCH, so "no body at all" can only be null where
            // the method allows it. An empty body under a declared type keeps
            // the type: a caller who said what an empty POST is meant that.
            is ClientRequest.Body.Empty -> when {
                request.method in BODYLESS -> null
                request.method in BODIED || declared != null -> ByteArray(0).toRequestBody(declared)
                else -> null
            }

            // Bytes rather than the `String` overload, which encodes with the
            // charset the declared type carries: the SPI says this body goes
            // out as UTF-8, and a `charset` parameter the caller wrote is a
            // claim about the wire rather than an instruction to re-encode.
            is ClientRequest.Body.Text -> body.content.toByteArray(Charsets.UTF_8).toRequestBody(declared)

            is ClientRequest.Body.Streaming ->
                StreamedBody(body.open, declared, request.header(CONTENT_LENGTH)?.toLongOrNull() ?: UNMEASURED)
        }
    }

    /**
     * The response, with its body still on the socket.
     *
     * OkHttp hands back the headers as they arrived, `Content-Type` and
     * `Content-Length` among them, so the reverse of the request side is to
     * leave them alone: adding either back from the body would send it twice.
     */
    private fun clientResponse(response: Response) = ClientResponse(
        status = response.code,
        headers = response.headers.toList(),
        body = BodyStream(response.body?.byteStream() ?: InputStream.nullInputStream()),
    )

    private companion object {
        val BODYLESS = setOf(Method.GET, Method.HEAD)
        val BODIED = setOf(Method.POST, Method.PUT, Method.PATCH)
    }
}

// File-level rather than in the companion above: a `const val` in a companion
// is a public static field on the enclosing class however private the companion
// is, and these three have no business being published surface.
private const val CONTENT_TYPE = "Content-Type"
private const val CONTENT_LENGTH = "Content-Length"

/** What OkHttp reads as "chunk it; nobody measured this body". */
private const val UNMEASURED = -1L

/** How long a connection may take to establish, the limit `pelican-client-java` also builds with. */
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)

/**
 * The client used when the caller supplies none.
 *
 * One per process rather than one per transport, because
 * `ClientTransport.default()` builds a fresh transport for every generated
 * client that asks for one, and a connection pool is not the sort of thing to
 * hold several of. Nothing here shuts it down: two transports sharing a client
 * must not be able to close each other's connections, so a caller who wants
 * that control is the caller who passed one in — which is also why its
 * dispatcher runs on daemon threads, where OkHttp's own does not.
 *
 * The read timeout replaces OkHttp's ten seconds, which bounds the wait for the
 * *next* chunk of a response rather than the call: left in place it would end
 * every `sse` subscription that went quiet for longer, including the ones the
 * caller set no deadline on at all. The connect timeout is the same five
 * seconds the JDK adapter builds its own client with.
 */
private val shared: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dispatcher(Dispatcher(daemonPool()))
        .connectTimeout(CONNECT_TIMEOUT)
        .readTimeout(Duration.ZERO)
        .build()
}

/**
 * What the shared client's dispatcher runs calls on: OkHttp's own pool, except
 * that the threads are daemons. A transport found through `ServiceLoader` is
 * one nobody was given a handle to, so its threads must not be what keeps a
 * finished process alive.
 */
private fun daemonPool(): ExecutorService = Executors.newCachedThreadPool { runnable ->
    Thread(runnable, "pelican-client-okhttp").apply { isDaemon = true }
}

/**
 * A [ClientRequest.Body.Streaming] on its way to the wire.
 *
 * `writeTo` is called when the connection is ready to take the bytes, and
 * called again when OkHttp sends the request a second time — a redirect, an
 * authenticated re-send — which is what `open` being a function is for.
 * `writeAll` copies at the speed the socket drains it, so an upload is never
 * held here.
 *
 * A caller who said how long the body is gets a sized request rather than a
 * chunked one, so the `Content-Length` they wrote is the one that goes out.
 */
private class StreamedBody(
    private val open: () -> InputStream,
    private val declared: MediaType?,
    private val length: Long,
) : RequestBody() {

    override fun contentType(): MediaType? = declared

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        open().source().use { sink.writeAll(it) }
    }
}

/**
 * The response body, guarded against the read that asks for nothing.
 *
 * `read(b, off, 0)` must return zero, and okio's own `InputStream` waits for
 * content before it looks at the length: the read that asks for nothing blocks
 * until the next chunk arrives, or until the server hangs up. Every
 * `readNBytes` ends with exactly that read, so a caller taking a fixed number
 * of bytes off a streamed response would wait for a chunk it had already been
 * given the bytes of.
 */
private class BodyStream(source: InputStream) : FilterInputStream(source) {

    override fun read(b: ByteArray, off: Int, len: Int): Int = if (len == 0) 0 else super.read(b, off, len)
}
