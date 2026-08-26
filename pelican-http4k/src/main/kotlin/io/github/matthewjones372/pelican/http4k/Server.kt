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
    /**
     * The interface [start] was asked for, which defaults to loopback. A
     * `ServerConfig` of your own owns its socket and may bind elsewhere;
     * http4k's `Http4kServer` reports only a port, so this is what was asked
     * rather than what the engine did with it.
     */
    val host: String,
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
 * Binds this API on [host]:[port]; port 0 lets the OS choose. [config] defaults
 * to [StreamingSunHttp], the JDK's own server, which needs no dependency beyond
 * this module and flushes each frame where http4k's stock `SunHttp` holds it
 * in a 4KB buffer.
 *
 * [handler] is how a module knowing more than this one — one serving an OpenAPI
 * document, or a service with routes of its own — wraps it.
 */
fun Api.start(
    port: Int = 8080,
    host: String = "127.0.0.1",
    config: ServerConfig = StreamingSunHttp(port, host),
    handler: Api.() -> HttpHandler = { toHttpHandler() },
): PelicanServer = PelicanServer(this, handler().asServer(config).start(), host)
