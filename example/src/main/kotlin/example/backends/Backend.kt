package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiErrorEnvelope
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.RefusalRenderer

/**
 * A backend, reduced to what anything outside it needs: bind the shared
 * descriptions with handlers of the right shape, start a server, say where to
 * reach it, stop.
 *
 * This is the seam that makes one test suite run against three interpreters.
 * The descriptions in `Greetings.kt` are already backend-agnostic; what is not
 * agnostic is the *binding* — `Source` on Pekko, `Sequence` on http4k, `Flow`
 * on Ktor — and that difference lives behind this interface and nowhere else in
 * the example. The server handle is no longer one of them: all three are
 * `AutoCloseable` with the same `block`, `stop` and `stopAsync`, which
 * `ServerShapeParityTest` holds.
 */
interface Backend {
    /** What the parameterised tests print, and what `Main` labels a server with. */
    val name: String

    /**
     * The shared endpoint descriptions bound to this backend's handlers.
     *
     * [outerFilters] run outside the service's own; see `greetingsApi`. It is
     * here rather than in each suite's own wiring so that a test which needs to
     * watch every request — `MetricsAcrossBackendsTest` does — can still ask
     * all three backends the same question through this one seam.
     *
     * [refusals] is the second thing a suite varies rather than a second
     * wiring: `RefusalsAcrossBackendsTest` runs the whole suite once per shipped
     * renderer, which is the same claim as running it once per backend.
     */
    fun api(
        outerFilters: List<Filter> = emptyList(),
        refusals: RefusalRenderer = ApiErrorEnvelope,
    ): Api

    /** Binds [api] on [port]; pass 0 to let the OS choose, which is what tests do. */
    fun start(
        port: Int = 0,
        outerFilters: List<Filter> = emptyList(),
        refusals: RefusalRenderer = ApiErrorEnvelope,
    ): Running
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
