package example.backends

import io.github.matthewjones372.pelican.Api

/**
 * A backend, reduced to what anything outside it needs: bind the shared
 * descriptions with handlers of the right shape, start a server, say where to
 * reach it, stop.
 *
 * This is the seam that makes one test suite run against three interpreters.
 * The descriptions in `Greetings.kt` are already backend-agnostic; what is not
 * agnostic is the *binding* — `Source` on Pekko, `Sequence` on http4k, `Flow`
 * on Ktor — and the shape of a server handle: Pekko's `stop()` returns a
 * `CompletionStage`, the other two return nothing. Both differences live behind
 * this interface, and nowhere else in the example.
 */
interface Backend {
    /** What the parameterised tests print, and what `Main` labels a server with. */
    val name: String

    /**
     * The shared endpoint descriptions bound to this backend's handlers.
     */
    fun api(): Api

    /** Binds [api] on [port]; pass 0 to let the OS choose, which is what tests do. */
    fun start(port: Int = 0): Running
}

/** A started server, and the two things a caller needs from one. */
interface Running : AutoCloseable {
    val baseUrl: String

    fun stop()

    override fun close() = stop()
}

/**
 * Every backend the example can run, in one list.
 */
val allBackends: List<Backend> = listOf(OnPekko, OnHttp4k, OnKtor)
