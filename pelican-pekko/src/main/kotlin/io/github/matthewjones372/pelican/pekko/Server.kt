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
     * Unbinds the port, and terminates the actor system if this server started
     * it. The stage completes once the system has actually terminated, so a
     * test joining on it does not return with threads still up.
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
 * Binds this API on [host]:[port]; port 0 lets the OS choose. [route] is how a
 * module knowing more than this one — one serving an OpenAPI document, or a
 * service with routes of its own — adds to it.
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
 * Binds this API on a system you already have, so a service that is more than
 * its HTTP layer does not run two of everything an `ActorSystem` carries.
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
