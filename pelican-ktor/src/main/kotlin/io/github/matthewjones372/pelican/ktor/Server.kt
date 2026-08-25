package io.github.matthewjones372.pelican.ktor

import io.github.matthewjones372.pelican.Api
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

/** A bound server, and the handle to shut it down again. */
class PelicanServer internal constructor(
    /** The API this server is serving. A test client needs its codecs. */
    val api: Api,
    val server: EmbeddedServer<*, *>,
) : AutoCloseable {
    /**
     * The port actually bound, for when port 0 was asked for. Ktor reports its
     * connectors from a suspending function and this is a blocking API, but
     * the engine has bound by the time [start] returns.
     */
    val port: Int get() = runBlocking { server.engine.resolvedConnectors().first().port }

    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun stop() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
    }

    /** Parks the calling thread until the server is stopped — what a `main` wants. */
    fun block() {
        runBlocking { server.application.coroutineContext.job.join() }
    }

    override fun close() = stop()
}

/**
 * Binds this API on [port]; port 0 lets the OS choose. [factory] defaults to
 * `CIO`, which ships with this module, so a Pelican service on Ktor needs no
 * further dependency.
 *
 * [module] is how a module knowing more than this one — one serving an OpenAPI
 * document, or a service with routes of its own — configures the application.
 */
fun Api.start(
    port: Int = 8080,
    host: String = "0.0.0.0",
    factory: ApplicationEngineFactory<out ApplicationEngine, *> = CIO,
    module: Application.(Api) -> Unit = { pelican(it) },
): PelicanServer {
    val api = this
    val server = embeddedServer(factory, port = port, host = host) { module(api) }
    return PelicanServer(api, server.start(wait = false))
}
