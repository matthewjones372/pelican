package io.github.matthewjones372.pelican.test.pekko

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.toRoute
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.Transport
import io.github.matthewjones372.pelican.test.apiClient
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
 * Runs requests straight through the interpreted route: no socket, but not a
 * shortcut either — `Route.function` seals the route, so matching, decoding and
 * the rejection-to-status rules are a bound server's.
 *
 * It does not exercise chunk framing, connection handling or TLS.
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
     * The raw exchange, entity unconsumed — the escape hatch for what
     * [Transport] flattens away. A chunked entity is still lazy, so
     * delivery-timing assertions work in memory as over a socket.
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
     * Shuts the system down and waits for it to be down. `terminate()` is
     * asynchronous, so returning straight after it would let a suite stack up
     * systems and their thread pools while reporting each one closed.
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

    // Content-Type belongs to the entity in Pekko's model.
    headers.filterNot { it.first.equals("Content-Type", ignoreCase = true) }
        .forEach { (name, value) -> req = req.addHeader(HttpHeader.parse(name, value)) }

    val payload = body
    if (payload != null) {
        val declared = headers.firstOrNull { it.first.equals("Content-Type", ignoreCase = true) }
        val contentType = declared?.let { ContentTypes.parse(it.second) } ?: ContentTypes.APPLICATION_JSON
        // Pekko models a multipart content type as binary, so the bytes go
        // over instead. UTF-8 either way.
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
 * A client that talks to this API in memory. Owns an `ActorSystem`, so close it
 * when the suite is done; pass an existing one to share it across classes,
 * which is the main cost left in an in-memory suite.
 */
fun Api.inMemory(systemName: String = "pelican-test"): ApiClient =
    ApiClient(
        InMemoryTransport(this, ActorSystem.create(Behaviors.empty(), systemName), ownsSystem = true),
        codecs,
    )

fun Api.inMemory(system: ActorSystem<Void>): ApiClient =
    ApiClient(InMemoryTransport(this, system, ownsSystem = false), codecs)

/**
 * A client for a server this process started; the codecs come from the [Api] it
 * serves. Here rather than in `pelican-test`, which would otherwise drag Pekko
 * onto every consumer's test classpath.
 */
fun PelicanServer.client(): ApiClient = apiClient(baseUrl, api.codecs)
