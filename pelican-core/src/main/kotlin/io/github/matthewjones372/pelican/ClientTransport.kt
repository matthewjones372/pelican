package io.github.matthewjones372.pelican

import java.io.InputStream
import java.time.Duration
import java.util.ServiceLoader
import java.util.concurrent.CompletionStage

/**
 * One outbound HTTP exchange, in terms core already owns: no `java.net.http`,
 * no Ktor, no Pekko.
 *
 * A generated client builds one of these and hands it to a [ClientTransport].
 * The URL is already assembled and already percent-encoded, because working out
 * what a repeated query parameter looks like on the wire is the description's
 * business rather than the transport's, and two transports working it out
 * separately would be two chances to disagree.
 */
class ClientRequest internal constructor(
    val method: Method,
    /** Absolute, already percent-encoded, query string included. */
    val url: String,
    /**
     * In the order the client wrote them, one pair per occurrence, so a header
     * a caller sent twice arrives twice.
     */
    val headers: List<Pair<String, String>>,
    val body: Body,
    /**
     * How long the exchange may take, or null to leave it to the transport's
     * own configuration. Per request rather than per transport because one
     * client's slow report and its cheap lookup are the same connection pool.
     */
    val timeout: Duration?,
) {
    /**
     * What a request always has. Anything beyond it is a `with` below rather
     * than a parameter here, so that a setting added later leaves the calls a
     * generated client already makes exactly where they are.
     */
    constructor(
        method: Method,
        url: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: Body = Body.Empty,
    ) : this(method, url, headers, body, null)

    /** The same request, with how long the exchange may take. */
    fun withTimeout(timeout: Duration?): ClientRequest =
        ClientRequest(method, url, headers, body, timeout)

    /** The same request carrying one more header, in the place it was added. */
    fun withHeader(name: String, value: String): ClientRequest =
        ClientRequest(method, url, headers + (name to value), body, timeout)

    /** The first value sent under [name], matched without regard to case. */
    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    override fun toString() = "$method $url"

    /**
     * What the request carries.
     *
     * Three shapes rather than one, because the difference between them is the
     * difference between a body a transport may hold and a body it must not: a
     * multipart upload is a file on somebody else's disk, and holding it here
     * as a `ByteArray` would undo the whole point of streaming it.
     */
    sealed interface Body {
        /** No body at all, which is not the same as an empty one. */
        data object Empty : Body

        /** A body already rendered, and sent as UTF-8. */
        class Text(val content: String) : Body

        /**
         * A body read from a stream as the transport drains it.
         *
         * A function rather than the stream itself: a transport that has to
         * send the request again — a redirect, a retry — needs a way to ask for
         * the bytes a second time, and one that cannot supply them can say so
         * rather than send a body that has already been consumed.
         */
        class Streaming(val open: () -> InputStream) : Body
    }
}

/**
 * What a transport answers with: the status, the headers, and a body that has
 * not been read yet.
 *
 * Unread on purpose. An `ndjson` or `sse` response is meant to be decoded as it
 * arrives, and a `bytes()` response may be larger than the process — so the
 * crossing hands over a stream and lets the caller decide, rather than handing
 * over a `String` that could only ever have been the whole thing.
 */
class ClientResponse(
    val status: Int,
    val headers: List<Pair<String, String>>,
    /** Unread. Whoever takes this response is the one who closes it. */
    val body: InputStream,
) {
    /**
     * The first value sent under [name], matched without regard to case, or
     * null where the server said nothing under that name.
     */
    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** The whole body as UTF-8 text, read and closed. */
    fun text(): String = body.use { it.readBytes().toString(Charsets.UTF_8) }
}

/**
 * Where a generated client's requests go.
 *
 * The point of the interface is that a service already running one HTTP client
 * does not acquire a second one because it generated a Pelican client: the
 * engine it has already tuned implements this, and the generated code never
 * learns which one it was given.
 *
 * The answer is a [CompletionStage] rather than a blocking value or a `suspend`
 * function because the generated code is written against this interface before
 * anyone has chosen a transport, so the shape cannot vary per adapter without
 * needing a generator per adapter. Given one shape it has to be the widest, and
 * the conversion only runs one way: an asynchronous transport serves a blocking
 * caller with a `join`, while a blocking transport serves an asynchronous
 * client by tying up a thread per call, which is most of what an asynchronous
 * client is for. It is also the shape core already uses for this job in the
 * other direction — see `ServerEndpoint.invoke`.
 */
fun interface ClientTransport {

    fun send(request: ClientRequest): CompletionStage<ClientResponse>

    companion object {
        /**
         * The transport a generated client uses when its caller names none:
         * whichever one the classpath supplies, found through [ServiceLoader].
         *
         * Core cannot depend on an adapter — its runtime classpath is the
         * Kotlin standard library and nothing else, which is a test — so the
         * default cannot be a name written down here. Adding
         * `pelican-client-java` is what makes it the JDK's own `HttpClient`.
         *
         * Loaded through core's own class loader rather than the thread's,
         * whose context is whatever the framework running the call last set it
         * to.
         */
        fun default(): ClientTransport {
            val found = ServiceLoader
                .load(ClientTransport::class.java, ClientTransport::class.java.classLoader)
                .toList()

            return when (found.size) {
                1 -> found.single()

                0 -> error(
                    "No ClientTransport is on the classpath. Add pelican-client-java to send with the JDK's " +
                        "own HttpClient, or pass a transport of your own.",
                )

                else -> error(
                    "Several transports are on the classpath — ${found.map { it::class.java.name }} — and " +
                        "nothing here can say which one this client should use. Pass one.",
                )
            }
        }
    }
}
