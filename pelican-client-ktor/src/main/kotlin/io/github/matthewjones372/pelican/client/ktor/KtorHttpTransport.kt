package io.github.matthewjones372.pelican.client.ktor

import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.ClientTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.utils.EmptyContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FilterInputStream
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * A [ClientTransport] over Ktor's `HttpClient`, for a caller who would rather
 * send through the client their service already configures than acquire a
 * second one.
 *
 * Declared as a service provider, so adding this module is enough to choose it
 * — but see [ClientTransport.default], which refuses to guess when a second
 * adapter is also on the classpath.
 */
class KtorHttpTransport @JvmOverloads constructor(
    /**
     * The client to send on. A service that has configured one hands it over
     * and keeps its engine, its plugins and its lifecycle; null takes the one
     * this module keeps for callers who have none.
     */
    client: HttpClient? = null,
) : ClientTransport {

    /**
     * Resolved on the first request rather than in the constructor, because
     * [ClientTransport.default] instantiates every provider it finds in order
     * to count them: a transport that built an engine while being counted would
     * leave one running behind the error that followed.
     */
    private val running: HttpClient by lazy { client ?: shared }

    /**
     * Where a `send` is launched: a child of the client's own job, so that a
     * closed client cancels the calls made on it and nothing here outlives the
     * thing it was sending with.
     *
     * A supervisor, so that one failed exchange is one failed exchange rather
     * than the end of every other call sharing the client. It carries no
     * dispatcher of its own — the client's is the one the work belongs on.
     */
    private val calls: CoroutineScope by lazy {
        CoroutineScope(running.coroutineContext + SupervisorJob(running.coroutineContext[Job]))
    }

    /** Whether a per-request deadline set on the request would reach the engine; see [deadline]. */
    private val honoursTimeouts: Boolean by lazy { running.pluginOrNull(HttpTimeout) != null }

    /**
     * Ktor's client suspends and this interface does not, so the crossing is a
     * coroutine writing into a [CompletableFuture] rather than a thread waiting
     * on one.
     *
     * The stage completes when the response head arrives, while the coroutine
     * stays suspended until the body's stream is closed — see [exchange]. Which
     * makes cancellation two-directional and both directions necessary: a stage
     * the caller cancels cancels the exchange, and an exchange that fails
     * before the head arrives fails the stage. Neither call can win twice,
     * since a `CompletableFuture` completes once.
     */
    override fun send(request: ClientRequest): CompletionStage<ClientResponse> {
        val answer = CompletableFuture<ClientResponse>()
        val exchange = calls.launch { exchange(request, answer) }
        val deadline = request.timeout
            ?.takeUnless { honoursTimeouts }
            ?.let { limit -> deadline(request, limit, answer) }

        exchange.invokeOnCompletion { failure ->
            // Null where the exchange ran to the end of the body, and a failure
            // arriving after the head has already been handed over is the
            // reader's to see, on the stream, rather than this stage's.
            if (failure != null) answer.completeExceptionally(failure)
        }
        answer.whenComplete { _, failure ->
            deadline?.cancel()
            // Cancelled, or failed before the head arrived. Either way the
            // exchange has nobody left to answer, and an exchange nobody is
            // waiting on must not keep holding a connection.
            if (failure != null) exchange.cancel()
        }

        return answer
    }

    /**
     * The per-request [java.time.Duration] where Ktor will not take it: on the
     * stage, expiring the call when the response head has not arrived in time.
     *
     * A request carries the deadline as a capability, and only the `HttpTimeout`
     * plugin turns that capability into a cancellation — a client handed over
     * without the plugin installed drops the deadline in silence, which is the
     * one thing an adapter must not let happen to a promise the SPI makes. So
     * where the client cannot honour it, this does, raising the exception Ktor
     * would have raised so that a caller catching a timeout by type catches it
     * whichever of the two imposed it.
     *
     * The two do not bound the same thing, and the difference is real: Ktor's
     * request timeout ends the whole exchange, the reading of a streamed body
     * included, while this one is discarded the moment the head arrives and
     * leaves the body to the client's own socket and idle timeouts.
     */
    private fun deadline(request: ClientRequest, limit: Duration, answer: CompletableFuture<ClientResponse>): Job =
        calls.launch {
            delay(limit.toMillis())
            answer.completeExceptionally(HttpRequestTimeoutException(request.url, limit.toMillis()))
        }

    /**
     * `prepareRequest` and `execute` rather than a plain call, because that is
     * the pair that leaves the response body on the socket: inside the block
     * the body is a live channel, and Ktor releases the connection as soon as
     * the block returns.
     *
     * So the block does not return until the caller has finished with the
     * stream. A caller who neither reads to the end nor closes is the one case
     * this cannot cover — the exchange stays open, holding its connection,
     * until the client is closed or its own timeout ends it.
     */
    private suspend fun exchange(request: ClientRequest, answer: CompletableFuture<ClientResponse>) {
        running.prepareRequest(ktorRequest(request)).execute { response ->
            val finished = CompletableDeferred<Unit>()
            // False where the stage was cancelled while the head was in flight.
            // Returning here releases the connection rather than leaving it
            // open for a response nobody will read.
            if (answer.complete(clientResponse(response, finished))) finished.await()
        }
    }

    private fun ktorRequest(request: ClientRequest): HttpRequestBuilder = HttpRequestBuilder().apply {
        method = HttpMethod.parse(request.method.name)
        url(request.url)

        // A declared failure is a status this client is expected to read, so a
        // handed-over client configured to throw on 4xx must not throw here.
        expectSuccess = false

        // Ktor carries these two on the body rather than in the header list,
        // and renders the body's copy in preference to a header of the same
        // name. They are read off here and put where Ktor keeps them.
        request.headers
            .filterNot { (name, _) -> name.equals(HttpHeaders.ContentType, true) }
            .filterNot { (name, _) -> name.equals(HttpHeaders.ContentLength, true) }
            .forEach { (name, value) -> headers.append(name, value) }

        // Where the client cannot honour it, `deadline` does instead.
        if (honoursTimeouts) request.timeout?.let { limit -> timeout { requestTimeoutMillis = limit.toMillis() } }

        setBody(content(request))
    }

    private fun content(request: ClientRequest): OutgoingContent {
        val declared = request.header(HttpHeaders.ContentType)?.let { ContentType.parse(it) }
        val length = request.header(HttpHeaders.ContentLength)?.toLongOrNull()

        return when (val body = request.body) {
            // An empty body under a declared type keeps the type: a caller who
            // said what an empty POST is meant that. `EmptyContent` has no type
            // to declare, so saying so takes the byte array of length zero.
            is ClientRequest.Body.Empty ->
                if (declared == null) EmptyContent else ByteArrayContent(ByteArray(0), declared)

            // Bytes rather than `TextContent`, which encodes with the charset
            // the declared type carries: the SPI says this body goes out as
            // UTF-8, and a `charset` parameter the caller wrote is a claim
            // about the wire rather than an instruction to re-encode.
            is ClientRequest.Body.Text ->
                ByteArrayContent(body.content.toByteArray(Charsets.UTF_8), declared ?: ContentType.Text.Plain)

            is ClientRequest.Body.Streaming -> StreamedContent(body.open, declared, length)
        }
    }

    /**
     * The response, with its body still on the socket.
     *
     * Ktor hands back the headers as they arrived, `Content-Type` and
     * `Content-Length` among them, so the reverse of the request side is to
     * leave them alone: adding either back from the body would send it twice.
     */
    private suspend fun clientResponse(response: HttpResponse, finished: CompletableDeferred<Unit>) = ClientResponse(
        status = response.status.value,
        headers = response.headers.entries().flatMap { (name, values) -> values.map { name to it } },
        body = Handover(response.bodyAsChannel().toInputStream(), finished),
    )

    private companion object {

        /**
         * The client used when the caller supplies none.
         *
         * One per process rather than one per transport, because
         * `ClientTransport.default()` builds a fresh transport for every
         * generated client that asks for one, and an engine is not the sort of
         * thing to hold several of. Nothing here closes it: two transports
         * sharing a client must not be able to shut each other down, so a
         * caller who wants that control is the caller who passed one in — which
         * is also why the engine's threads have to be daemons, as CIO's are.
         *
         * The infinite request timeout replaces CIO's own fifteen seconds,
         * which is a deadline on the whole exchange rather than on reaching the
         * response head: left in place it would cut off every `sse` response
         * that stayed open longer, including the ones the caller set no timeout
         * on at all. A request carrying a [ClientRequest.timeout] overrides it.
         * CIO's five-second connect timeout is left where it is, being the same
         * limit `pelican-client-java` builds its own client with.
         */
        private val shared: HttpClient by lazy {
            HttpClient(CIO) {
                install(HttpTimeout) { requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS }
            }
        }
    }
}

/** How much of an upload is held in memory at once while it is copied to the socket. */
private const val COPY_BUFFER = 8 * 1024

/**
 * A [ClientRequest.Body.Streaming] on its way to the wire.
 *
 * `WriteChannelContent` is asked for the bytes when the connection is ready to
 * take them, and asked again when Ktor sends the request a second time — a
 * redirect, a retry — which is what `open` being a function is for. Writing to
 * a full channel suspends, so an upload is read at the speed the socket drains
 * it and nothing is held here but one buffer.
 *
 * A caller who said how long the body is gets a sized request rather than a
 * chunked one, so the `Content-Length` they wrote is the one that goes out.
 */
private class StreamedContent(
    private val open: () -> InputStream,
    declared: ContentType?,
    override val contentLength: Long?,
) : OutgoingContent.WriteChannelContent() {

    override val contentType: ContentType = declared ?: ContentType.Application.OctetStream

    override suspend fun writeTo(channel: ByteWriteChannel) {
        // `read` blocks, and the thread it would block is one of the engine's.
        withContext(Dispatchers.IO) {
            open().use { source ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    channel.writeFully(buffer, 0, read)
                    channel.flush()
                }
            }
        }
    }
}

/**
 * The response body, and the thing that tells the exchange the caller is done
 * with it.
 *
 * Exhausting the stream counts as well as closing it, so a caller who read to
 * the end and let the stream fall out of scope still releases the connection.
 * Both are idempotent: whichever happens first is the one that ends the
 * exchange.
 */
private class Handover(source: InputStream, private val finished: CompletableDeferred<Unit>) :
    FilterInputStream(source) {

    override fun read(): Int = super.read().also { if (it < 0) finished.complete(Unit) }

    /**
     * `read(b, off, 0)` must return zero, and Ktor's own bridge waits for
     * content before it looks at the length: the read that asks for nothing
     * blocks until the next chunk arrives, or until the server hangs up. Every
     * `readNBytes` ends with exactly that read, so a caller taking a fixed
     * number of bytes off a streamed response would wait for a chunk it had
     * already been given the bytes of.
     */
    override fun read(b: ByteArray, off: Int, len: Int): Int =
        if (len == 0) 0 else super.read(b, off, len).also { if (it < 0) finished.complete(Unit) }

    override fun close() {
        try {
            super.close()
        } finally {
            finished.complete(Unit)
        }
    }
}
