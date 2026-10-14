package io.github.matthewjones372.pelican.client.jetty

import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.ClientTransport
import org.eclipse.jetty.client.BytesRequestContent
import org.eclipse.jetty.client.HttpClient
import org.eclipse.jetty.client.InputStreamRequestContent
import org.eclipse.jetty.client.InputStreamResponseListener
import org.eclipse.jetty.client.ProxyAuthenticationProtocolHandler
import org.eclipse.jetty.client.Request
import org.eclipse.jetty.client.Response
import org.eclipse.jetty.client.Result
import org.eclipse.jetty.client.WWWAuthenticationProtocolHandler
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val CONTENT_LENGTH = "Content-Length"

private const val CONNECT_TIMEOUT_MILLIS = 5_000L

/**
 * A [ClientTransport] over Jetty's `HttpClient`, the same stack `pelican-jetty`
 * serves with.
 *
 * Declared as a service provider, so adding this module is enough to choose it,
 * but see [ClientTransport.default], which refuses to guess when a second
 * adapter is also on the classpath.
 */
class JettyHttpTransport private constructor(
    private val client: Lazy<HttpClient>,
    private val owned: Boolean,
) : ClientTransport, AutoCloseable {

    /**
     * Owns a client of its own, started on the first request rather than here
     * because [ClientTransport.default] instantiates every provider to count them.
     */
    constructor() : this(lazy { ownClient() }, owned = true)

    /**
     * Borrows a client its owner started and will stop, such as one sharing the
     * server's threads. Its protocol handlers stay as the owner set them, so a
     * declared 401 without a challenge fails unless `www-authenticate` is removed.
     */
    constructor(http: HttpClient) : this(lazyOf(http), owned = false)

    private val http: HttpClient by client

    override fun send(request: ClientRequest): CompletionStage<ClientResponse> {
        val answer = CompletableFuture<ClientResponse>()
        val exchange = jettyRequest(request)
        exchange.send(HeadListener(answer, http))
        request.timeout?.let { deadline(request, answer, it) }
        answer.whenComplete { _, failure -> if (failure != null) exchange.abort(failure) }
        return answer
    }

    /** Stops the client this transport started; a borrowed one is left to its owner. */
    override fun close() {
        if (owned && client.isInitialized()) http.stop()
    }

    /**
     * Bounds the arrival of the head and not the reading of the body: Jetty's
     * `Request.timeout` covers the whole exchange, and an `sse` response that
     * outlives it is the endpoint working. A `TimeoutException`, not an
     * `IOException`, so the default retry policy does not resend a request the
     * server may already have.
     */
    private fun deadline(request: ClientRequest, answer: CompletableFuture<ClientResponse>, timeout: Duration) {
        CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute {
            answer.completeExceptionally(TimeoutException("$request timed out after $timeout"))
        }
    }

    private fun jettyRequest(request: ClientRequest): Request {
        val length = request.header(CONTENT_LENGTH)?.toLongOrNull()
        val built = http.newRequest(request.url)
            .method(request.method.name)
            // Jetty writes `Content-Length` from the body's own length.
            .headers { fields ->
                request.headers
                    .filterNot { (name, _) -> name.equals(CONTENT_LENGTH, ignoreCase = true) }
                    .forEach { (name, value) -> fields.add(name, value) }
            }
        return when (val body = request.body) {
            is ClientRequest.Body.Empty -> built
            is ClientRequest.Body.Text -> built.body(BytesRequestContent(null as String?, body.content.toByteArray()))
            is ClientRequest.Body.Streaming -> built.body(Upload(body.open(), length))
        }
    }
}

/** Read as the socket is ready to write; a length the caller declared makes it sized, not chunked. */
private class Upload(stream: InputStream, private val length: Long?) : InputStreamRequestContent(null, stream) {
    override fun getLength(): Long = length?.takeIf { it > 0 } ?: -1
}

/**
 * Completes [answer] when the head arrives, leaving the body on the socket for
 * the caller to read. Completed on the client's executor, because a caller who
 * reads the body inside the callback would otherwise block the thread that
 * delivers it.
 */
private class HeadListener(
    private val answer: CompletableFuture<ClientResponse>,
    private val http: HttpClient,
) : InputStreamResponseListener() {

    override fun onHeaders(response: Response) {
        super.onHeaders(response)
        val crossed = ClientResponse(
            status = response.status,
            headers = response.headers.map { it.name to it.value },
            body = inputStream,
        )
        http.executor.execute { if (!answer.complete(crossed)) crossed.body.close() }
    }

    override fun onComplete(result: Result) {
        super.onComplete(result)
        if (result.isFailed) answer.completeExceptionally(asIo(result.failure))
    }
}

/**
 * Daemon threads, as a transport found through `ServiceLoader` has nobody
 * holding it to close. Without the authentication handlers, which fail a 401
 * that carries no challenge rather than handing over the declared failure.
 */
private fun ownClient(): HttpClient = HttpClient().apply {
    executor = QueuedThreadPool().apply {
        name = "pelican-client"
        isDaemon = true
    }
    scheduler = ScheduledExecutorScheduler("pelican-client-scheduler", true)
    connectTimeout = CONNECT_TIMEOUT_MILLIS
    isFollowRedirects = false
    start()
    protocolHandlers.remove(WWWAuthenticationProtocolHandler.NAME)
    protocolHandlers.remove(ProxyAuthenticationProtocolHandler.NAME)
}

/**
 * An exchange cancelled by anything but the caller crosses as the
 * `IOException` `RetryPolicy` retries by default, as spec 0052 made Pekko's do.
 */
internal fun asIo(failed: Throwable): Throwable {
    val cause = if (failed is CompletionException) failed.cause ?: failed else failed
    return if (cause is CancellationException) {
        IOException("The exchange was cancelled before a response arrived", cause)
    } else {
        cause
    }
}
