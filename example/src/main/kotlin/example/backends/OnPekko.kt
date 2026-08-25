package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.pekko.handledNow
import io.github.matthewjones372.pelican.pekko.handledOneOf
import io.github.matthewjones372.pelican.pekko.handledOrFail
import io.github.matthewjones372.pelican.pekko.handledWith
import io.github.matthewjones372.pelican.pekko.start
import io.github.matthewjones372.pelican.pekko.streamedNow
import org.apache.pekko.stream.javadsl.Source
import java.time.Duration

/**
 * The Pekko binding of [greetingEndpoints].
 *
 * Two handlers, and the only thing here that is Pekko-shaped is the `Source`:
 * `streamedNow` on this backend takes `(I) -> Source<T, NotUsed>`, so the
 * countdown is a throttled stream and back-pressure runs from the socket back
 * to it.
 */
val pekkoRoutes: List<ServerEndpoint> = listOf(
    greet handledNow { (who, shout) -> greetingOf(who, shout) },

    countdown streamedNow { start ->
        Source.range(start, 1, -1)
            .throttle(1, Duration.ofMillis(100))
            .map { seq -> tick(seq) }
    },

    // The one endpoint here that declares a failure, so this is the binder
    // that demands an `Outcome` — and the 429 it may answer with carries a
    // `Retry-After`, on all three backends, from the one description.
    echo handledOrFail { (trace, note) -> echoOrRefuse(trace, note) },

    remember handledOneOf { (who, note) -> rememberGreeting(who, note) },

    preferences handledNow { (locale, session) -> preferencesOf(locale, session) },

    signIn handledNow { form -> sessionOf(form) },

    uploadFile handledNow { (caption, notes, file) -> uploaded(caption, notes, file) },

    filters handledNow { (tags, ids, features, seen) -> filtersOf(tags, ids, features, seen) },

    strict handledNow { (term, key, jar) -> Strictly(term, key, jar) },

    motd handledNow { "Be excellent to each other." },

    forget handledWith { _ -> },
)

fun pekkoApi(outerFilters: List<Filter> = emptyList()): Api =
    greetingsApi(pekkoRoutes, outerFilters = outerFilters)

object OnPekko : Backend {
    override val name = "pekko"

    override fun api(outerFilters: List<Filter>): Api = pekkoApi(outerFilters)

    override fun start(port: Int, outerFilters: List<Filter>): Running {
        // The actor system is this backend's alone; nothing outside sees it.
        val server = api(outerFilters).start(port = port, systemName = "greetings-pekko")
        return object : Running {
            override val baseUrl = server.baseUrl
            override fun stop() {
                server.stop().toCompletableFuture().join()
            }
        }
    }
}
