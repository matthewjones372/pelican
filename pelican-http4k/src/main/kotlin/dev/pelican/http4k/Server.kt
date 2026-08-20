package dev.pelican.http4k

import dev.pelican.Api
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
 * Binds this API on [port]. Pass port 0 to let the OS choose one, which is
 * what the tests do.
 *
 * [config] is the http4k backend. The default is [StreamingSunHttp], which is
 * the JDK's own server and so needs no dependency beyond this module — and,
 * unlike http4k's stock `SunHttp`, it flushes each frame rather than holding it
 * in a 4KB buffer. Pass any other `ServerConfig` to swap it, adding that
 * http4k module to your build:
 *
 * ```
 * ordersApi().start(port = 8080)
 * ordersApi().start(port = 8080, config = Jetty(8080))
 * ```
 *
 * The backend decides how promptly a streamed frame reaches the wire. This
 * module hands the server one frame per read (see `FrameInputStream`), but a
 * backend that aggregates small writes will hold a frame until its own buffer
 * fills. Measured with ten rows produced 100ms apart: `Jetty` and the default
 * here deliver the first in about a tenth of a second, http4k's `SunHttp` and
 * `Undertow` deliver all ten at the end.
 *
 * [handler] exists so that a module which knows more than this one — serving
 * an OpenAPI document alongside the endpoints, say — can wrap the handler
 * without this module having to know about it. The default is the endpoints
 * alone.
 */
fun Api.start(
    port: Int = 8080,
    config: ServerConfig = StreamingSunHttp(port),
    handler: Api.() -> HttpHandler = { toHttpHandler() },
): PelicanServer = PelicanServer(this, handler().asServer(config).start())
