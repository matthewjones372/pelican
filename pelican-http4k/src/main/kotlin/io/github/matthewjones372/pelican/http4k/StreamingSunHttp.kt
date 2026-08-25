package io.github.matthewjones372.pelican.http4k

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
 * http4k's stock `SunHttp` copies the body without flushing, so the JDK's
 * chunked stream holds frames until its 4KB buffer fills: ten NDJSON rows
 * produced 100ms apart arrive together at the end. `Undertow` is the same,
 * `Jetty` is not. Flushing after each write is the whole difference, and
 * `StreamingTimingTest` is the measurement.
 *
 * Otherwise http4k's `SunHttp`, whose source says to duplicate and modify it.
 * For real load pass a production backend — `Jetty(port)`, `Undertow(port)` —
 * noting that only some of them stream.
 *
 * [executor] is a cached pool rather than a work-stealing one, because a
 * streaming handler holds its thread for as long as the stream runs. The pool
 * is therefore unbounded, which is another reason a busy service wants a real
 * backend.
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
        // The response has likely been started, so there is nothing useful
        // left to say. Matches what http4k's own server does.
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
 * Writes the response, flushing after every read. It only matters for streamed
 * bodies, where one read is one frame (see [FrameInputStream]) and the flush is
 * what puts it on the wire instead of in a buffer.
 */
private fun HttpExchange.respondWith(response: Response) {
    response.headers.forEach { (name, value) -> responseHeaders.add(name, value.orEmpty()) }

    if (requestMethod == "HEAD" || response.status == Status.NO_CONTENT) {
        sendResponseHeaders(response.status.code, -1)
        return
    }

    // Length 0 means "unknown, chunk it" to this server, which is what a
    // streamed body has.
    sendResponseHeaders(response.status.code, response.body.length ?: 0)

    val buffer = ByteArray(COPY_BUFFER_BYTES)
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

/** Two pages, the usual size for a copy loop that is not trying to be clever. */
private const val COPY_BUFFER_BYTES = 8192
