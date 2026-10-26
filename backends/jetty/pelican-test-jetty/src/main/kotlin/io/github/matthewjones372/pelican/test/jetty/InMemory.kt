package io.github.matthewjones372.pelican.test.jetty

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.jetty.Handlers
import io.github.matthewjones372.pelican.jetty.PELICAN_URI_COMPLIANCE
import io.github.matthewjones372.pelican.jetty.PelicanServer
import io.github.matthewjones372.pelican.jetty.Unanswered
import io.github.matthewjones372.pelican.jetty.toHandler
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.RequestSpec
import io.github.matthewjones372.pelican.test.ResponseSpec
import io.github.matthewjones372.pelican.test.Transport
import io.github.matthewjones372.pelican.test.apiClient
import org.eclipse.jetty.http.HttpTester
import org.eclipse.jetty.http.HttpVersion
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.LocalConnector
import org.eclipse.jetty.server.Server
import java.util.concurrent.TimeUnit

/**
 * Runs requests through a started Jetty `Server` on a `LocalConnector`: no socket, but the bytes are parsed as a
 * bound server parses them, so URI compliance, framing and Jetty's own refusals are a real server's.
 */
class InMemoryTransport(handler: Handler) : Transport, AutoCloseable {

    val server = Server()
    private val connector = LocalConnector(
        server,
        HttpConnectionFactory(HttpConfiguration().apply { uriCompliance = PELICAN_URI_COMPLIANCE }),
    )

    /** How long [exchange] waits for a whole response. */
    var responseTimeoutSeconds: Long = 30

    init {
        server.addConnector(connector)
        server.handler = handler
        server.defaultHandler = Unanswered()
        var started = false
        try {
            server.start()
            started = true
        } finally {
            if (!started) server.stop()
        }
    }

    /** The raw exchange, for what [Transport] flattens away. */
    fun exchange(request: HttpTester.Request): HttpTester.Response {
        val raw = connector.getResponse(request.generate(), responseTimeoutSeconds, TimeUnit.SECONDS)
            ?: error("No response within $responseTimeoutSeconds seconds")
        return HttpTester.parseResponse(raw)
    }

    override fun send(request: RequestSpec): ResponseSpec {
        val res = exchange(request.toJetty())
        return ResponseSpec(
            res.status,
            res.map { it.name to it.value },
            res.contentBytes ?: ByteArray(0),
        )
    }

    /** Stops Jetty, which returns once its threads are down. */
    override fun close() {
        server.stop()
    }
}

private fun RequestSpec.toJetty(): HttpTester.Request {
    val req = HttpTester.newRequest()
    req.method = method.name
    req.uri = target
    req.version = HttpVersion.HTTP_1_1
    // HTTP/1.1 refuses a request with no Host; a test rarely names one.
    if (headers.none { it.first.equals("Host", ignoreCase = true) }) req.put("Host", "in-memory")
    headers.forEach { (name, value) -> req.add(name, value) }
    body?.let { payload ->
        if (headers.none { it.first.equals("Content-Type", ignoreCase = true) }) {
            req.put("Content-Type", "application/json")
        }
        req.setContent(payload.toByteArray(Charsets.UTF_8))
    }
    return req
}

/**
 * A client that talks to this API through Jetty in memory. Owns a started `Server`, so close it when the suite is
 * done. [handler] is how a module serving more than the endpoints, such as the document, is tested the same way.
 */
fun Api.inMemory(
    handlers: Handlers = Handlers.default,
    handler: Api.() -> Handler = { toHandler(handlers) },
): ApiClient = ApiClient(InMemoryTransport(handler()), codecs)

/** A client for a server this process started; the codecs come from the [Api] it serves. */
fun PelicanServer.client(): ApiClient = apiClient(baseUrl, api.codecs)
