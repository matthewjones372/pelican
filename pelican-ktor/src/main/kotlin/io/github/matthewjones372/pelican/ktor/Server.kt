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
     * The port actually bound, which is the one to use after asking for port 0.
     *
     * Ktor reports its connectors from a suspending function and this is a
     * blocking API, so the answer is waited for here. The engine has bound by
     * the time [start] returns, so the wait is nominal.
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
 * Binds this API on [port]. Pass port 0 to let the OS choose one, which is
 * what the tests do.
 *
 * [factory] is the Ktor engine. The default is `CIO`, which ships with this
 * module, so a Pelican service on Ktor needs no further dependency. Pass any
 * other engine factory to swap it, adding that Ktor module to your build:
 *
 * ```
 * ordersApi().start(port = 8080)
 * ordersApi().start(port = 8080, factory = Netty)
 * ```
 *
 * Unlike the other two backends, the engine has little say in how promptly a
 * streamed frame reaches the wire: the response writer flushes each frame as it
 * is encoded (see `Responses.kt`), so rows produced 100ms apart arrive 100ms
 * apart rather than in one burst at the end.
 *
 * [module] exists so that a module which knows more than this one — serving an
 * OpenAPI document alongside the endpoints, say — can configure the application
 * without this module having to know about it. The default is the endpoints
 * alone.
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
