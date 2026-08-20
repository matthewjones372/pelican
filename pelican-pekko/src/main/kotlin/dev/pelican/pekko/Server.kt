package dev.pelican.pekko

import dev.pelican.Api
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.http.javadsl.Http
import org.apache.pekko.http.javadsl.ServerBinding
import org.apache.pekko.http.javadsl.server.Route
import java.util.concurrent.CompletionStage

/** A bound server, and the handle to shut it down again. */
class PelicanServer internal constructor(
    /** The API this server is serving. A test client needs its codecs. */
    val api: Api,
    val system: ActorSystem<Void>,
    val binding: ServerBinding,
) {
    val port: Int get() = binding.localAddress().port
    val baseUrl: String get() = "http://127.0.0.1:$port"

    /**
     * Unbinds the port and shuts the actor system down. The stage completes
     * when the system has actually terminated, not merely when termination was
     * asked for — otherwise a test that joins on this returns while the
     * system's threads are still up.
     */
    fun stop(): CompletionStage<Void> =
        binding.unbind()
            .thenCompose {
                system.terminate()
                system.getWhenTerminated()
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
        val binding = Http.get(system)
            .newServerAt(host, port)
            .bind(route(system))
            .toCompletableFuture()
            .join()
        PelicanServer(this, system, binding)
    } catch (t: Throwable) {
        system.terminate()
        throw t
    }
}
