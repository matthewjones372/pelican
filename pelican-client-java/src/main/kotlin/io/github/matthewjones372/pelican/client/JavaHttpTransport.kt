package io.github.matthewjones372.pelican.client

import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.ClientTransport
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private const val CONTENT_LENGTH = "Content-Length"

/** How long a connection may take to establish before the call gives up. */
private const val CONNECT_TIMEOUT_SECONDS = 5L

/**
 * A [ClientTransport] over the JDK's own `HttpClient`, for a service that runs
 * no other HTTP stack to call out of.
 *
 * Declared as a service provider, so adding this module is enough to choose it
 * — but see [ClientTransport.default], which refuses to guess when a second
 * adapter is also on the classpath.
 */
class JavaHttpTransport @JvmOverloads constructor(
    /** A client the service has already tuned, or one with a five second connect timeout. */
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build(),
) : ClientTransport {

    /**
     * The body handler is `ofInputStream` for every response, so the stage
     * completes when the head arrives and the bytes stay on the socket until
     * the caller reads them.
     */
    override fun send(request: ClientRequest): CompletionStage<ClientResponse> {
        val exchange = http.sendAsync(jdkRequest(request), HttpResponse.BodyHandlers.ofInputStream())

        // A future of this stage's own rather than `thenApply`: cancellation
        // does not travel back up a chain of stages, so a caller who gives up
        // has it carried to the exchange here, and a response that arrives
        // after they did is closed rather than left holding its connection.
        val answer = CompletableFuture<ClientResponse>()
        exchange.whenComplete { response, failure ->
            when {
                failure != null -> answer.completeExceptionally(asIo(failure))

                answer.isDone -> response.body().close()

                else -> {
                    val crossed = ClientResponse(
                        status = response.statusCode(),
                        headers = response.headers().map().entries
                            .flatMap { (name, values) -> values.map { name to it } },
                        body = response.body(),
                    )
                    if (!answer.complete(crossed)) crossed.body.close()
                }
            }
        }
        request.timeout?.let { deadline(request, answer, it) }
        answer.whenComplete { _, failure -> if (failure != null) exchange.cancel(true) }
        return answer
    }

    /**
     * The deadline bounds the arrival of the head and not the reading of the
     * body, as Pekko's does: `HttpRequest.timeout` would also close a body
     * still streaming, and an `sse` response that outlives it is the endpoint
     * working. A `TimeoutException`, not an `IOException`, so the default
     * retry policy does not resend a request the server may already have.
     */
    private fun deadline(request: ClientRequest, answer: CompletableFuture<ClientResponse>, timeout: Duration) {
        CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute {
            answer.completeExceptionally(TimeoutException("$request timed out after $timeout"))
        }
    }

    private fun jdkRequest(request: ClientRequest): HttpRequest {
        val length = request.header(CONTENT_LENGTH)?.toLongOrNull()
        val builder = HttpRequest
            .newBuilder(URI.create(request.url))
            .method(request.method.name, publisher(request.body, length))

        // `header` adds rather than replaces, so a name sent twice goes out
        // twice. `Content-Length` is one the JDK refuses as a header and
        // writes itself from the publisher's length.
        request.headers
            .filterNot { (name, _) -> name.equals(CONTENT_LENGTH, ignoreCase = true) }
            .forEach { (name, value) -> builder.header(name, value) }

        return builder.build()
    }

    private fun publisher(body: ClientRequest.Body, length: Long?): HttpRequest.BodyPublisher = when (body) {
        is ClientRequest.Body.Empty -> HttpRequest.BodyPublishers.noBody()

        is ClientRequest.Body.Text -> HttpRequest.BodyPublishers.ofString(body.content)

        // Asked for when the socket is ready to write, so an upload is never
        // held here. A length the caller declared makes it sized, not chunked.
        is ClientRequest.Body.Streaming -> {
            val stream = HttpRequest.BodyPublishers.ofInputStream { body.open() }
            if (length == null || length <= 0) stream else HttpRequest.BodyPublishers.fromPublisher(stream, length)
        }
    }
}

/**
 * The JDK's failures, unwrapped. An exchange cancelled by anything but the
 * caller crosses as the `IOException` `RetryPolicy` retries by default, as
 * spec 0052 made Pekko's do.
 */
internal fun asIo(failed: Throwable): Throwable {
    val cause = if (failed is CompletionException) failed.cause ?: failed else failed
    return if (cause is CancellationException) {
        IOException("The exchange was cancelled before a response arrived", cause)
    } else {
        cause
    }
}
