package io.github.matthewjones372.pelican.client.pekko

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.ClientTransport
import io.github.matthewjones372.pelican.Method
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.http.javadsl.Http
import org.apache.pekko.http.javadsl.model.ContentType
import org.apache.pekko.http.javadsl.model.ContentTypes
import org.apache.pekko.http.javadsl.model.HttpEntities
import org.apache.pekko.http.javadsl.model.HttpHeader
import org.apache.pekko.http.javadsl.model.HttpMethod
import org.apache.pekko.http.javadsl.model.HttpMethods
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.model.HttpResponse
import org.apache.pekko.http.javadsl.model.RequestEntity
import org.apache.pekko.stream.StreamTcpException
import org.apache.pekko.stream.javadsl.Source
import org.apache.pekko.stream.javadsl.StreamConverters
import org.apache.pekko.util.ByteString
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// File-level rather than in a companion: `const` in a private companion is
// still a public static field on the class, so these would be frozen into the
// published surface by a dump that has no reason to describe them.
private const val CONTENT_TYPE = "Content-Type"
private const val CONTENT_LENGTH = "Content-Length"

private val READ_LIMIT: Duration = Duration.ofDays(1)

/**
 * A [ClientTransport] over Pekko HTTP's client, for a caller who would rather
 * send through the stack their service already runs than acquire a second one.
 *
 * Declared as a service provider, so adding this module is enough to choose it
 * — but see [ClientTransport.default], which refuses to guess when a second
 * adapter is also on the classpath.
 */
class PekkoHttpTransport @JvmOverloads constructor(
    /**
     * The system to run on. A service that already has one hands it over and
     * keeps its dispatchers, its configuration and its shutdown; null takes the
     * one this module keeps for callers who have none.
     */
    system: ClassicActorSystemProvider? = null,
) : ClientTransport {

    /**
     * Resolved on the first request rather than in the constructor, because
     * [ClientTransport.default] instantiates every provider it finds in order
     * to count them: a transport that started an actor system while being
     * counted would leave one running behind the error that followed.
     */
    private val running: ClassicActorSystemProvider by lazy { system ?: shared }

    private val http: Http by lazy { Http.get(running) }

    override fun send(request: ClientRequest): CompletionStage<ClientResponse> {
        val exchange = http.singleRequest(pekkoRequest(request))
            .exceptionally { failed -> throw asIo(failed) }
        val head = request.timeout?.let { deadline(request, exchange, it) } ?: exchange

        // A future of this stage's own rather than `thenApply`, so that a
        // caller's `cancel` is seen here: a response arriving after the caller
        // gave up is discarded at once, not left holding its connection until
        // the pool's subscription timeout salvages it.
        val answer = CompletableFuture<ClientResponse>()
        head.whenComplete { response, failure ->
            when {
                failure != null -> answer.completeExceptionally(failure)

                answer.isDone -> response.discardEntityBytes(running)

                else -> {
                    val crossed = clientResponse(response)
                    if (!answer.complete(crossed)) crossed.body.close()
                }
            }
        }
        return answer
    }

    /**
     * Pekko raises a connection refused or reset as `StreamTcpException`, a
     * `RuntimeException` — so it would not read as the `IOException` the other
     * transports raise and `RetryPolicy` retries by default. It crosses here as
     * one, with Pekko's own as its cause.
     */
    private fun asIo(failed: Throwable): Throwable {
        val cause = if (failed is CompletionException) failed.cause ?: failed else failed
        return if (cause is StreamTcpException) IOException(cause.message, cause) else cause
    }

    /**
     * The per-request [Duration], applied to the arrival of the response head
     * and not to the reading of its body.
     *
     * Pekko HTTP has no per-request deadline to map onto: its timeouts are
     * connection pool settings, and passing per-request settings to
     * `singleRequest` keys a new pool per distinct value. So the deadline is
     * imposed here, on the stage, which bounds exactly what the JDK adapter's
     * `HttpRequest.timeout` bounds — a streamed body is still governed by the
     * pool's own idle timeout, since an `sse` response that outlives its
     * deadline is the endpoint working rather than failing.
     */
    private fun deadline(
        request: ClientRequest,
        exchange: CompletionStage<HttpResponse>,
        timeout: Duration,
    ): CompletionStage<HttpResponse> {
        val head = CompletableFuture<HttpResponse>()
        exchange.whenComplete { response, failure ->
            when {
                failure != null -> head.completeExceptionally(failure)

                // The deadline won. Nobody will read this entity, and an unread
                // entity holds its connection until the pool times it out.
                !head.complete(response) -> response.discardEntityBytes(running)
            }
        }
        return head
            .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .exceptionally { failed -> throw described(failed, request, timeout) }
    }

    /** `orTimeout` raises a `TimeoutException` with no message, which names neither call nor limit. */
    private fun described(failed: Throwable, request: ClientRequest, timeout: Duration): Throwable {
        val cause = if (failed is CompletionException) failed.cause ?: failed else failed
        return if (cause is TimeoutException) TimeoutException("$request timed out after $timeout") else cause
    }

    private fun pekkoRequest(request: ClientRequest): HttpRequest {
        // Pekko carries these two on the entity rather than in the header list,
        // and drops them with a warning if they are added as headers. They are
        // read off here and put where Pekko keeps them.
        val declared = request.header(CONTENT_TYPE)?.let { ContentTypes.parse(it) }
        val length = request.header(CONTENT_LENGTH)?.toLongOrNull()

        val headers = request.headers
            .filterNot { (name, _) -> name.equals(CONTENT_TYPE, true) || name.equals(CONTENT_LENGTH, true) }
            .map { (name, value) -> HttpHeader.parse(name, value) }

        return HttpRequest.create(request.url)
            .withMethod(pekkoMethod(request.method))
            .addHeaders(headers)
            .withEntity(entity(request.body, declared, length))
    }

    /**
     * Pekko has no entity without a content type, so a body sent under no
     * declared type is sent under the plainest one that fits it rather than
     * under none.
     */
    private fun entity(body: ClientRequest.Body, declared: ContentType?, length: Long?): RequestEntity = when (body) {
        // An empty body under a declared type keeps the type: a caller who said
        // what an empty POST is meant that.
        is ClientRequest.Body.Empty ->
            if (declared == null) HttpEntities.EMPTY else HttpEntities.create(declared, ByteArray(0))

        // Strict, so Pekko renders the true Content-Length and any the caller
        // guessed at is beside the point.
        is ClientRequest.Body.Text ->
            HttpEntities.create(declared ?: ContentTypes.TEXT_PLAIN_UTF8, body.content.toByteArray(Charsets.UTF_8))

        // `fromInputStream` asks for the stream when the connection is ready to
        // write it, and asks again if the request is materialised a second
        // time, which is what `open` being a function is for. Nothing is held.
        is ClientRequest.Body.Streaming -> {
            val bytes = StreamConverters.fromInputStream { body.open() }
            val type = declared ?: ContentTypes.APPLICATION_OCTET_STREAM
            // A caller who said how long the body is gets a sized request
            // rather than a chunked one, so the Content-Length they wrote is
            // the one that goes out. Pekko has no sized entity of length zero,
            // so that one is chunked like any other unmeasured body.
            if (length == null || length <= 0) {
                HttpEntities.createChunked(type, bytes)
            } else {
                HttpEntities.create(type, length, bytes)
            }
        }
    }

    /**
     * The response, with its body still on the socket.
     *
     * `withoutSizeLimit` because a response entity carries whatever
     * `max-content-length` the system was configured with, and a `bytes()`
     * response the descriptions promise may be larger than the process. Pekko
     * leaves that limit off for a client by default; a service that set one to
     * bound what its *server* accepts should not thereby have capped what its
     * calls may read back.
     */
    private fun clientResponse(response: HttpResponse): ClientResponse {
        val entity = response.entity().withoutSizeLimit()

        // The reverse of the request side: what Pekko took out of the header
        // list is put back, so a caller reading `Content-Type` off the response
        // finds it whichever adapter carried the call. Pekko excludes both from
        // `getHeaders`, so this adds rather than duplicates. A status that
        // allows no entity gets neither, rather than a fabricated zero length.
        val fromEntity = if (!response.status().allowsEntity()) {
            emptyList()
        } else {
            listOfNotNull(
                (CONTENT_TYPE to entity.contentType.value())
                    .takeIf { entity.contentType != ContentTypes.NO_CONTENT_TYPE },
                entity.contentLengthOption.takeIf { it.isPresent }?.let { CONTENT_LENGTH to it.asLong.toString() },
            )
        }

        return ClientResponse(
            status = response.status().intValue(),
            headers = fromEntity + response.getHeaders().map { it.name() to it.value() },
            body = bodyStream(entity.dataBytes),
        )
    }

    /**
     * Pekko's `Source` to the SPI's `InputStream`.
     *
     * `asInputStream` materialises the response into a bounded queue the reader
     * drains, so a slow caller backpressures the connection rather than filling
     * memory, and closing the stream cancels the source. A caller who neither
     * reads nor closes is the one case this cannot cover: the stream stays
     * materialised, backpressuring, until the pool's idle timeout fails it.
     *
     * The read limit is not a request deadline. It fires when no chunk arrives
     * for that long, and the connection's own idle timeout is what notices a
     * dead peer first, so anything short enough to be a deadline would instead
     * be a bug in every `sse` response that goes quiet between events.
     */
    private fun bodyStream(bytes: Source<ByteString, *>): InputStream =
        bytes.runWith(StreamConverters.asInputStream(READ_LIMIT), running)

    private fun pekkoMethod(method: Method): HttpMethod =
        HttpMethods.lookup(method.name).orElseThrow { IllegalArgumentException("Pekko knows no method ${method.name}") }
}

/**
 * The system a transport runs on when its caller brought none.
 *
 * One per process rather than one per transport, because an actor system is
 * not the sort of thing to hold several of, and because `ClientTransport
 * .default()` builds a fresh transport for every generated client that asks
 * for one. Nothing here terminates it: two transports sharing a system must not
 * be able to shut each other down, and a caller who wants that control passes a
 * system of their own.
 *
 * `daemonic` for the same reason. A transport found through `ServiceLoader` is
 * one nobody was given a handle to, so its threads must not be what keeps a
 * finished process alive.
 */
private val shared: ActorSystem<Void> by lazy {
    ActorSystem.create(
        Behaviors.empty(),
        "pelican-client",
        ConfigFactory.parseString("pekko.daemonic = on").withFallback(ConfigFactory.load()),
    )
}
