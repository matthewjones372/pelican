package dev.pelican.test.pekko

import dev.pelican.Api
import dev.pelican.Method
import dev.pelican.pekko.PelicanServer
import dev.pelican.pekko.toRoute
import dev.pelican.test.ApiClient
import dev.pelican.test.RequestSpec
import dev.pelican.test.ResponseSpec
import dev.pelican.test.Transport
import dev.pelican.test.apiClient
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.http.javadsl.model.ContentType
import org.apache.pekko.http.javadsl.model.ContentTypes
import org.apache.pekko.http.javadsl.model.HttpEntities
import org.apache.pekko.http.javadsl.model.HttpHeader
import org.apache.pekko.http.javadsl.model.HttpMethod
import org.apache.pekko.http.javadsl.model.HttpMethods
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.apache.pekko.http.javadsl.model.HttpResponse
import org.apache.pekko.http.javadsl.model.Uri
import java.util.concurrent.TimeUnit

/**
 * Runs requests straight through the interpreted route. No socket, no port, no
 * bind — but not a shortcut either: `Route.function` seals the route, so path
 * matching, parameter decoding, body handling, response building and the
 * rejection-to-status rules are all the ones a bound server would use.
 *
 * What it does *not* exercise is everything below the route: chunk framing on
 * the wire, connection handling, TLS. Keep a socket-level test for those.
 */
class InMemoryTransport(
    private val api: Api,
    val system: ActorSystem<Void>,
    private val ownsSystem: Boolean,
) : Transport, AutoCloseable {

    private val handler = api.toRoute(system).function(system)

    /** How long [close] waits for the system to actually terminate. */
    var shutdownTimeoutSeconds: Long = 10

    /**
     * The raw exchange, with the response entity still unconsumed.
     *
     * The escape hatch for anything [Transport] flattens away: a chunked
     * entity is still lazy here, so back-pressure and delivery-timing
     * assertions work in memory exactly as they do over a socket.
     */
    fun exchange(request: HttpRequest): HttpResponse =
        handler.apply(request).toCompletableFuture().join()

    override fun send(request: RequestSpec): ResponseSpec {
        val res = exchange(request.toPekko())
        val strict = res.entity()
            .toStrict(api.strictBodyTimeoutMillis, system)
            .toCompletableFuture()
            .join()

        val headers = res.getHeaders().map { it.name() to it.value() } +
            listOf("Content-Type" to strict.contentType.toString())

        return ResponseSpec(res.status().intValue(), headers, strict.data.utf8String())
    }

    /**
     * Shuts the system down and *waits* for it to be down.
     *
     * `terminate()` is asynchronous, so returning straight after it would let a
     * suite that opens several clients stack up systems and their thread pools
     * while reporting each one closed. This is what `ActorTestKit`'s
     * `shutdownTestKit` does; doing it here avoids a testkit dependency on this
     * module's main classpath for the one behaviour that matters.
     */
    override fun close() {
        if (!ownsSystem) return
        system.terminate()
        system.getWhenTerminated().toCompletableFuture().get(shutdownTimeoutSeconds, TimeUnit.SECONDS)
    }
}

private fun RequestSpec.toPekko(): HttpRequest {
    var req = HttpRequest.create()
        .withMethod(method.toPekkoMethod())
        .withUri(Uri.create(target))

    // Content-Type belongs to the entity in Pekko's model, so it is set with
    // the body rather than added as a header.
    headers.filterNot { it.first.equals("Content-Type", ignoreCase = true) }
        .forEach { (name, value) -> req = req.addHeader(HttpHeader.parse(name, value)) }

    val payload = body
    if (payload != null) {
        val declared = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
        val contentType = declared?.let { ContentTypes.parse(it.second) } ?: ContentTypes.APPLICATION_JSON
        // A multipart content type is not one Pekko will take a String for —
        // it carries a boundary and is modelled as binary — so the bytes are
        // handed over instead. UTF-8 either way, which is what the String
        // already was.
        req = req.withEntity(
            if (contentType is ContentType.NonBinary) HttpEntities.create(contentType, payload)
            else HttpEntities.create(contentType, payload.toByteArray(Charsets.UTF_8)),
        )
    }
    return req
}

private fun Method.toPekkoMethod(): HttpMethod = when (this) {
    Method.GET -> HttpMethods.GET
    Method.POST -> HttpMethods.POST
    Method.PUT -> HttpMethods.PUT
    Method.PATCH -> HttpMethods.PATCH
    Method.DELETE -> HttpMethods.DELETE
    Method.HEAD -> HttpMethods.HEAD
    Method.OPTIONS -> HttpMethods.OPTIONS
}

/**
 * A client that talks to this API in memory.
 *
 * ```
 * val app = ordersApi().inMemory()
 * ```
 *
 * Owns an `ActorSystem`, so close it when the suite is done. Pass an existing
 * system instead to share one across test classes — creating one per class is
 * the main cost left in an in-memory suite.
 */
fun Api.inMemory(systemName: String = "pelican-test"): ApiClient =
    ApiClient(
        InMemoryTransport(this, ActorSystem.create(Behaviors.empty(), systemName), ownsSystem = true),
        codecs,
    )

fun Api.inMemory(system: ActorSystem<Void>): ApiClient =
    ApiClient(InMemoryTransport(this, system, ownsSystem = false), codecs)

/**
 * A client for a server this process started. The codecs come from the [Api]
 * it is serving, so there is nothing to keep in sync:
 *
 * ```
 * val server = ordersApi().start(port = 0)
 * val app = server.client()
 * ```
 *
 * This lived in `pelican-test` until it was the only reason that module
 * dragged Pekko onto every consumer's test classpath. It is one function; the
 * transport it builds is the JDK's own.
 */
fun PelicanServer.client(): ApiClient = apiClient(baseUrl, api.codecs)
