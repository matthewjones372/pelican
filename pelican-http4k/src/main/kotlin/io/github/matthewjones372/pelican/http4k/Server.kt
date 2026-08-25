package io.github.matthewjones372.pelican.http4k

import io.github.matthewjones372.pelican.Api
import org.http4k.core.HttpHandler
import org.http4k.server.Http4kServer
import org.http4k.server.ServerConfig
import org.http4k.server.asServer

/** A bound server, and the handle to shut it down again. */
class PelicanServer internal constructor(
    /** The API this server is serving. A test client needs its codecs. */
    val api: Api,
    val server: Http4kServer,
) : AutoCloseable {
    /** The port actually bound, which is the one to use after asking for port 0. */
    val port: Int get() = server.port()

    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun stop() {
        server.stop()
    }

    /** Parks the calling thread until the process is stopped — what a `main` wants. */
    fun block(): Unit = server.block()

    override fun close() = stop()
}

/**
 * Binds this API on [port]; port 0 lets the OS choose. [config] defaults to
 * [StreamingSunHttp], the JDK's own server, which needs no dependency beyond
 * this module and flushes each frame where http4k's stock `SunHttp` holds it
 * in a 4KB buffer.
 *
 * The backend decides how promptly a streamed frame reaches the wire: measured
 * with ten rows 100ms apart, `Jetty` and the default deliver the first in about
 * a tenth of a second, while `SunHttp` and `Undertow` deliver all ten at the end.
 *
 * [handler] is how a module knowing more than this one — one serving an OpenAPI
 * document, or a service with routes of its own — wraps it.
 */
fun Api.start(
    port: Int = 8080,
    config: ServerConfig = StreamingSunHttp(port),
    handler: Api.() -> HttpHandler = { toHttpHandler() },
): PelicanServer = PelicanServer(this, handler().asServer(config).start())
