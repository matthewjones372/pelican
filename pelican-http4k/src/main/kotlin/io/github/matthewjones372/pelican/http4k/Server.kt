package io.github.matthewjones372.pelican.http4k

import io.github.matthewjones372.pelican.Api
import org.http4k.core.HttpHandler
import org.http4k.server.Http4kServer
import org.http4k.server.ServerConfig
import org.http4k.server.asServer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch

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
    private val stopped = CountDownLatch(1)

    /** The port actually bound, which is the one to use after asking for port 0. */
    val port: Int get() = server.port()

    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun stop() {
        server.stop()
        stopped.countDown()
    }

    /** [stop] off the calling thread: the shape Pekko needs, spelled the same here. */
    fun stopAsync(): CompletionStage<Unit> = CompletableFuture.supplyAsync { stop() }

    /**
     * Parks the calling thread until [stop] — what a `main` wants. Not
     * `Http4kServer.block()`, which is `Thread.currentThread().join()` and so
     * is released by stopping the server no more than by anything else.
     */
    fun block() {
        stopped.await()
    }

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
