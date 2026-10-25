package io.github.matthewjones372.pelican.test.gannet

import io.github.matthewjones372.gannet.GannetConfig
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.gannet.GannetHandler
import io.github.matthewjones372.pelican.gannet.Handlers
import io.github.matthewjones372.pelican.gannet.PelicanServer
import io.github.matthewjones372.pelican.gannet.start
import io.github.matthewjones372.pelican.gannet.toHandler
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.HttpClientTransport
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.Transport
import io.github.matthewjones372.pelican.test.apiClient

/**
 * Runs requests through a Gannet server this transport started on a loopback port of the kernel's choosing, and
 * stops it on [close]. Gannet has no in-memory connector, so the bytes cross a socket, parsed as any caller's are.
 */
class LoopbackTransport(val server: PelicanServer) : Transport, AutoCloseable {
    private val http = HttpClientTransport(server.baseUrl)

    override fun send(request: RequestSpec): ResponseSpec = http.send(request)

    /** Stops the server, which returns once its carriers are down. */
    override fun close() = server.stop()
}

/**
 * A client that talks to this API through Gannet on loopback, as `inMemory` does on Jetty and Pekko. Owns a started
 * server, so close it when the suite is done. [config] sizes Gannet, with its TLS left off so the client needs no
 * trust; [handler] is how a module serving more than the endpoints, such as the document, is tested the same way.
 *
 * @throws io.github.matthewjones372.gannet.GannetUnavailableException where Gannet cannot run.
 */
fun Api.onLoopback(
    handlers: Handlers = Handlers.default,
    config: GannetConfig = GannetConfig(),
    handler: Api.() -> GannetHandler = { toHandler(handlers) },
): ApiClient {
    val server = start(port = 0, handlers = handlers, config = config.copy(tls = null), handler = handler)
    return ApiClient(LoopbackTransport(server), codecs)
}

/** A client for a server this process started; the codecs come from the [Api] it serves. */
fun PelicanServer.client(): ApiClient = apiClient(baseUrl, api.codecs)
