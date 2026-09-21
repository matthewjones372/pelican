package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.Api
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.http.javadsl.Http
import org.apache.pekko.http.javadsl.ServerBinding
import org.apache.pekko.http.javadsl.server.Route
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** A bound server, and the handle to shut it down again. */
class PelicanServer internal constructor(
    /** The API this server is serving. A test client needs its codecs. */
    val api: Api,
    val system: ActorSystem<Void>,
    val binding: ServerBinding,
    /** The interface this server was bound to, which defaults to loopback. */
    val host: String,
    /**
     * Whether [system] was created by [start] rather than handed to it. Only
     * a system this server created is a system this server may terminate.
     */
    val ownsSystem: Boolean = true,
) : AutoCloseable {
    private val stopped = CountDownLatch(1)

    val port: Int get() = binding.localAddress().port
    val baseUrl: String get() = "http://127.0.0.1:$port"

    /**
     * [stopAsync], waited on: the blocking spelling all three backends share.
     * A caller wanting a different deadline awaits the stage itself, which is
     * why this takes no timeout and the backends keep one shape.
     */
    fun stop() {
        stopAsync().awaitTerminated(STOP_TIMEOUT)
    }

    /**
     * Unbinds the port, and terminates the actor system if this server started
     * it. The stage completes once the system has actually terminated, so a
     * test joining on it does not return with threads still up.
     */
    fun stopAsync(): CompletionStage<Unit> =
        binding.unbind()
            .thenCompose {
                if (!ownsSystem) {
                    CompletableFuture.completedStage(null)
                } else {
                    system.terminate()
                    system.getWhenTerminated()
                }
            }
            .thenApply { stopped.countDown() }

    /**
     * Parks the calling thread until [stop] — what a `main` wants. A latch
     * rather than `getWhenTerminated`, because a server on a borrowed system
     * never terminates one and would park for the life of the process.
     */
    fun block() {
        stopped.await()
    }

    override fun close() = stop()
}

/**
 * Waits for a Pekko shutdown stage, treating an interrupt boxed by the actor
 * system's own termination callbacks as termination: spec 0049 traces one to a
 * plain `ForkJoinPool.shutdown()` interrupting the worker running
 * `stopScheduler()`, which is housekeeping that runs after the guardian is
 * already dead. Anything else is rethrown, and a caller interrupted on its own
 * thread keeps its interrupt status.
 */
fun CompletionStage<*>.awaitTerminated(timeout: Duration) {
    try {
        toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (e: ExecutionException) {
        if (!e.hasInterruptedCause()) throw e
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw e
    }
}

/** The stage wraps Scala's boxed exception, so the interrupt is two causes down, not one. */
private fun Throwable.hasInterruptedCause(): Boolean {
    var cause: Throwable? = this
    var depth = 0
    while (cause != null && depth++ < MAX_CAUSE_DEPTH) {
        if (cause is InterruptedException) return true
        cause = cause.cause
    }
    return false
}

private const val MAX_CAUSE_DEPTH = 8

/** Long enough that a real shutdown never hits it, short enough that a stuck one fails a build. */
private val STOP_TIMEOUT: Duration = Duration.ofSeconds(30)

/**
 * Binds this API on [host]:[port]; port 0 lets the OS choose. [route] is how a
 * module knowing more than this one — one serving an OpenAPI document, or a
 * service with routes of its own — adds to it.
 */
fun Api.start(
    port: Int = 8080,
    host: String = "127.0.0.1",
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
    port: Int = 8080,
    host: String = "127.0.0.1",
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
    return PelicanServer(this, system, binding, host, ownsSystem)
}
