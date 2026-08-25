package io.github.matthewjones372.pelican.client

import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.ClientTransport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionStage

/**
 * A [ClientTransport] over the JDK's own `HttpClient`, and the one a generated
 * client uses when nobody names another.
 *
 * Declared as a service provider, so adding this module is the whole of
 * choosing it. It has a no-argument constructor for that reason; a service that
 * has tuned an `HttpClient` of its own passes it instead.
 */
class JavaHttpTransport @JvmOverloads constructor(
    private val http: HttpClient = defaultClient(),
) : ClientTransport {

    /**
     * `sendAsync` rather than `send`, since the answer is a [CompletionStage]
     * either way and the blocking form would only be this one waiting on the
     * caller's own thread before the caller had a chance to decide.
     *
     * The body handler is `ofInputStream` for every response, streamed or not:
     * the stage completes when the headers arrive, and the bytes are read off
     * the socket by whoever reads the stream. A caller that wants the body
     * whole reads it whole.
     */
    override fun send(request: ClientRequest): CompletionStage<ClientResponse> {
        val sent = http.sendAsync(jdkRequest(request), HttpResponse.BodyHandlers.ofInputStream())
        val answer = sent.thenApply { response ->
            ClientResponse(
                status = response.statusCode(),
                headers = response.headers().map().entries
                    .flatMap { (name, values) -> values.map { name to it } },
                body = response.body(),
            )
        }

        // Cancellation travels down a chain of stages and not back up it, so
        // cancelling the one handed out here would otherwise leave the exchange
        // it was derived from running: a socket held open, and a response body
        // nobody is left to read. A caller who gives up — a coroutine cancelled
        // while it was awaiting this — is asking for the request to stop, so
        // the cancellation is carried back to the exchange by hand.
        answer.whenComplete { _, failure -> if (failure is CancellationException) sent.cancel(true) }

        return answer
    }

    private fun jdkRequest(request: ClientRequest): HttpRequest {
        val builder = HttpRequest
            .newBuilder(URI.create(request.url))
            .method(request.method.name, publisher(request.body))

        request.timeout?.let { builder.timeout(it) }
        // `header` adds rather than replaces, which is what a request carrying
        // one name twice needs.
        request.headers.forEach { (name, value) -> builder.header(name, value) }

        return builder.build()
    }

    private fun publisher(body: ClientRequest.Body): HttpRequest.BodyPublisher = when (body) {
        is ClientRequest.Body.Empty -> HttpRequest.BodyPublishers.noBody()

        is ClientRequest.Body.Text -> HttpRequest.BodyPublishers.ofString(body.content)

        // `ofInputStream` takes a supplier and asks for the stream when it is
        // ready to write, so an upload is read at the speed the socket drains
        // and is never held here.
        is ClientRequest.Body.Streaming -> HttpRequest.BodyPublishers.ofInputStream { body.open() }
    }
}

/** How long a connection may take to establish before the call gives up. */
private const val CONNECT_TIMEOUT_SECONDS = 5L

/**
 * The client used when the caller supplies none. The connect timeout is the one
 * a generated client used to build for itself, kept so that moving the
 * transport out of the generated code changed nothing a caller could see.
 */
private fun defaultClient(): HttpClient = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
    .build()
