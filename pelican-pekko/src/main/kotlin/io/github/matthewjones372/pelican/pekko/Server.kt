package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.Api
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.http.javadsl.Http
import org.apache.pekko.http.javadsl.ServerBinding
import org.apache.pekko.http.javadsl.server.Route
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** A bound server, and the handle to shut it down again. */
class PelicanServer internal constructor(
    /** The API this server is serving. A test client needs its codecs. */
    val api: Api,
    val system: ActorSystem<Void>,
    val binding: ServerBinding,
    /**
     * Whether [system] was created by [start] rather than handed to it. Only
     * a system this server created is a system this server may terminate.
     */
    val ownsSystem: Boolean = true,
) {
    val port: Int get() = binding.localAddress().port
    val baseUrl: String get() = "http://127.0.0.1:$port"

    /**
     * Unbinds the port, and shuts the actor system down if this server started
     * it. The stage completes when the system has actually terminated, not
     * merely when termination was asked for — otherwise a test that joins on
     * this returns while the system's threads are still up.
     *
     * A *borrowed* system is only unbound from. Terminating it would take the
     * caller's cluster, persistence and streams down with the HTTP port, and
     * whoever created a system is who gets to end it — the same rule
     * `InMemoryTransport.close()` follows.
     */
    fun stop(): CompletionStage<Void> =
        binding.unbind()
            .thenCompose {
                if (!ownsSystem) {
                    CompletableFuture.completedStage(null)
                } else {
                    system.terminate()
                    system.getWhenTerminated()
                }
            }
            .thenApply { null }
}

/**
 * Binds this API on [host]:[port]. Pass port 0 to let the OS choose one, which
 * is what the tests do.
 *
 * [route] exists so that a module which knows more than this one — serving an
 * OpenAPI document alongside the endpoints, say — can add to the route without
 * this module having to know about it. The default is the endpoints alone.
 */
fun Api.start(
    host: String = "127.0.0.1",
    port: Int = 8080,
    systemName: String = "pelican",
    route: Api.(ActorSystem<Void>) -> Route = { toRoute(it) },
): PelicanServer {
    val system = ActorSystem.create(Behaviors.empty<Void>(), systemName)
    return try {
        bind(system, host, port, ownsSystem = true, route = route)
    } catch (t: Throwable) {
        system.terminate()
        throw t
    }
}

/**
 * Binds this API on a system you already have.
 *
 * A service that is more than its HTTP layer has an `ActorSystem` before it has
 * a route — for its cluster, its persistence, its streams — and starting a
 * second one to serve the endpoints means two of everything an actor system
 * carries. This is the same bind as [start] onto that system instead:
 *
 * ```
 * val system = ActorSystem.create(Behaviors.empty<Void>(), "orders")
 * val server = ordersApi().start(system, port = 8080)
 * ```
 *
 * The server does not terminate a system it did not create — [PelicanServer.stop]
 * unbinds the port and leaves the system running. `toRoute(system)` remains the
 * lower-level door for anyone binding the route themselves.
 */
fun Api.start(
    system: ActorSystem<Void>,
    host: String = "127.0.0.1",
    port: Int = 8080,
    route: Api.(ActorSystem<Void>) -> Route = { toRoute(it) },
): PelicanServer = bind(system, host, port, ownsSystem = false, route = route)

private fun Api.bind(
    system: ActorSystem<Void>,
    host: String,
    port: Int,
    ownsSystem: Boolean,
    route: Api.(ActorSystem<Void>) -> Route,
): PelicanServer {
    val binding = Http.get(system)
        .newServerAt(host, port)
        .bind(route(system))
        .toCompletableFuture()
        .join()
    return PelicanServer(this, system, binding, ownsSystem)
}
