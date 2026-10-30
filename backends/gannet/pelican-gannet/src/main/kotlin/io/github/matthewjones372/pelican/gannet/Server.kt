package io.github.matthewjones372.pelican.gannet

import io.github.matthewjones372.gannet.GannetConfig
import io.github.matthewjones372.gannet.GannetServer
import io.github.matthewjones372.gannet.GannetSupport
import io.github.matthewjones372.gannet.GannetUnavailableException
import io.github.matthewjones372.pelican.Api
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/** A bound server, and the handle to shut it down again. */
class PelicanServer internal constructor(
    /** The API this server is serving. A test client needs its codecs. */
    val api: Api,
    val server: GannetServer,
    /** The interface this server was bound to, which defaults to loopback. */
    val host: String,
    private val tls: Boolean,
) : AutoCloseable {
    val port: Int get() = server.port

    /** `https` when the configuration set [GannetConfig.tls], since the port then speaks only TLS. */
    val baseUrl: String get() = "${if (tls) "https" else "http"}://127.0.0.1:$port"

    /** [stopAsync], waited on: the blocking spelling every backend shares. */
    fun stop() {
        stopAsync().toCompletableFuture().get(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
    }

    /** Stops Gannet, which closes the port and joins its carriers; the stage completes once it has. */
    fun stopAsync(): CompletionStage<Unit> = CompletableFuture.runAsync { server.stop() }.thenApply { }

    /** Parks the calling thread until [stop]: what a `main` wants. */
    fun block() = server.awaitStopped()

    override fun close() = stop()
}

/** Long enough that a real shutdown never hits it, short enough that a stuck one fails a build. */
private val STOP_TIMEOUT: Duration = Duration.ofSeconds(30)

/**
 * Binds this API on [host]:[port]; port 0 lets the OS choose. [handler] is how a module knowing more than this one,
 * such as one serving an OpenAPI document, adds to it; a request every step passes on is answered with the 404 or
 * 405 Pekko would give. [config] sizes Gannet and sets its TLS ([GannetConfig.tls]); its own host and port are
 * replaced by these.
 *
 * @throws GannetUnavailableException when this machine or JVM cannot run Gannet: see [GannetSupport].
 */
fun Api.start(
    port: Int = 8080,
    host: String = "127.0.0.1",
    handlers: Handlers = Handlers.default,
    config: GannetConfig = GannetConfig(),
    handler: Api.() -> GannetHandler = { toHandler(handlers) },
): PelicanServer {
    require(admission == null) { "This interpreter does not support operation admission." }
    val server = GannetServer.start(config.copy(host = host, port = port), answering(handler(), responseHeaders))
    return PelicanServer(this, server, host, tls = config.tls != null)
}
