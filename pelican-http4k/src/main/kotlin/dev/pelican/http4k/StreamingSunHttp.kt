package dev.pelican.http4k

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.http4k.core.HttpHandler
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.RequestSource
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Uri
import org.http4k.server.Http4kServer
import org.http4k.server.ServerConfig
import org.http4k.server.ServerConfig.StopMode
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.MILLISECONDS

/**
 * The JDK's own HTTP server, wired so that a streamed response actually
 * streams.
 *
 * This exists because of a measurement rather than a preference. http4k's stock
 * `SunHttp` copies the response body to the socket without flushing, so the
 * JDK's chunked output stream holds frames until its own 4KB buffer fills: ten
 * NDJSON rows produced 100ms apart all arrive together at the end, a second
 * later. `Undertow` behaves the same way; `Jetty` does not. Flushing after each
 * write is the whole difference — the first row then lands in about 150ms, as
 * the description promised it would.
 *
 * Pelican's streaming outputs are a claim about when bytes leave the machine,
 * and enough of it is decided here that shipping a default which quietly broke
 * it would make the claim untrue. So this is what [dev.pelican.http4k.start]
 * binds by default, and it needs nothing beyond http4k-core and the JDK.
 * `StreamingTimingTest` is the measurement, kept as a test.
 *
 * It is otherwise http4k's `SunHttp`, whose own source says to duplicate and
 * modify it as required. For a service under real load, pass a production
 * backend instead — `Jetty(port)`, `Undertow(port)` — the way you would with
 * any http4k app, and note that only some of them stream:
 *
 * ```
 * ordersApi().start(port = 8080, config = Jetty(8080))
 * ```
 *
 * [executor] is a cached pool rather than the work-stealing pool http4k's
 * version uses, because a streaming handler holds its thread for as long as the
 * stream runs: on a pool sized to the CPU count, a handful of slow streams
 * would leave nothing to serve anything else with. The cost is that the pool is
 * unbounded, which is another reason a busy service wants a real backend.
 */
class StreamingSunHttp(
    private val port: Int = 8000,
    override val stopMode: StopMode = StopMode.Immediate,
    private val executor: ExecutorService = Executors.newCachedThreadPool(),
) : ServerConfig {

    override fun toServer(http: HttpHandler): Http4kServer = object : Http4kServer {
        private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 1000)

        override fun port(): Int = if (port > 0) port else server.address.port

        override fun start(): Http4kServer = apply {
            server.createContext("/") { exchange -> exchange.handle(http) }
            server.executor = executor
            server.start()
        }

        override fun stop(): Http4kServer = apply {
            if (stopMode is StopMode.Graceful) {
                executor.shutdown()
                executor.awaitTermination(stopMode.timeout.toMillis(), MILLISECONDS)
            }
            server.stop(0)
            executor.shutdownNow()
        }
    }
}

private fun HttpExchange.handle(http: HttpHandler) {
    try {
        val response = toRequest()?.let(http) ?: Response(Status.NOT_IMPLEMENTED)
        respondWith(response)
    } catch (_: Exception) {
        // The response has very likely been started by now, so there is nothing
        // useful left to say; this matches what http4k's own server does.
        runCatching { sendResponseHeaders(500, -1) }
    } finally {
        close()
    }
}

private fun HttpExchange.toRequest(): Request? =
    supportedMethod(requestMethod)?.let { method ->
        val uri = requestURI.rawQuery
            ?.let { Uri.of(requestURI.rawPath).query(it) }
            ?: Uri.of(requestURI.rawPath)
        Request(method, uri)
            .body(requestBody, requestHeaders.getFirst("Content-Length")?.toLongOrNull())
            .headers(requestHeaders.toList().flatMap { (name, values) -> values.map { name to it } })
            .source(RequestSource(remoteAddress.address.hostAddress, remoteAddress.port))
    }

private fun supportedMethod(name: String): Method? = Method.entries.firstOrNull { it.name == name }

/**
 * Writes the response, flushing after every read.
 *
 * A response with a known length is written the same way; the flushing only
 * matters for the streamed ones, where each read of the body is exactly one
 * frame (see [FrameInputStream]) and the flush is what puts that frame on the
 * wire instead of in a buffer.
 */
private fun HttpExchange.respondWith(response: Response) {
    response.headers.forEach { (name, value) -> responseHeaders.add(name, value ?: "") }

    if (requestMethod == "HEAD" || response.status == Status.NO_CONTENT) {
        sendResponseHeaders(response.status.code, -1)
        return
    }

    // Length 0 means "unknown, chunk it" to this server — which is what a
    // streamed body has, because giving it a length would mean buffering it.
    sendResponseHeaders(response.status.code, response.body.length ?: 0)

    val buffer = ByteArray(8192)
    response.body.stream.use { input ->
        responseBody.use { output ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
            }
        }
    }
}
