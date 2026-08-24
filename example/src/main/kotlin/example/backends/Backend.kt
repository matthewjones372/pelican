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
 *
 * Deliberately thin. It is not an abstraction over HTTP servers in general —
 * anything more would start to hide the differences the example exists to show.
 * A test that wants a backend's own types still reaches for that backend's
 * module directly, as `MethodMismatchTest` does.
 */
interface Backend {
    /** What the parameterised tests print, and what `Main` labels a server with. */
    val name: String

    /**
     * The shared endpoint descriptions bound to this backend's handlers.
     *
     * Every backend builds it through [greetingsApi], so the codecs, title and
     * version cannot drift apart between them — which is what lets a test
     * compare the generated documents byte for byte.
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
 *
 * A test parameterised over this list gains a backend by adding a line here —
 * and gains it with the same assertions, which is the point: a new interpreter
 * is not "done" because it compiles, it is done when it answers the existing
 * questions the same way.
 */
val allBackends: List<Backend> = listOf(OnPekko, OnHttp4k, OnKtor)
