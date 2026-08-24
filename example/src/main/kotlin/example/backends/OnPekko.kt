package example.backends

import dev.pelican.Api
import dev.pelican.ServerEndpoint
import dev.pelican.pekko.handledNow
import dev.pelican.pekko.handledOneOf
import dev.pelican.pekko.handledOrFail
import dev.pelican.pekko.start
import dev.pelican.pekko.streamedNow
import org.apache.pekko.stream.javadsl.Source
import java.time.Duration

/**
 * The Pekko binding of [greetingEndpoints].
 *
 * Two handlers, and the only thing here that is Pekko-shaped is the `Source`:
 * `streamedNow` on this backend takes `(I) -> Source<T, NotUsed>`, so the
 * countdown is a throttled stream and back-pressure runs from the socket back
 * to it.
 *
 * Compare with `OnHttp4k.kt` and `OnKtor.kt`, line for line.
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

    // The one endpoint here that answers two ways. The binder demands an
    // `Outcome` for the same reason `handledOrFail` does — the handler names
    // the response it is producing — and the name says what the alternatives
    // are, which here is two successes rather than a failure.
    remember handledOneOf { (who, note) -> rememberGreeting(who, note) },

    preferences handledNow { (locale, session) -> preferencesOf(locale, session) },

    signIn handledNow { form -> sessionOf(form) },

    uploadFile handledNow { (caption, notes, file) -> uploaded(caption, notes, file) },

    filters handledNow { (tags, ids, features, seen) -> filtersOf(tags, ids, features, seen) },
)

fun pekkoApi(): Api = greetingsApi(pekkoRoutes)

object OnPekko : Backend {
    override val name = "pekko"

    override fun api(): Api = pekkoApi()

    override fun start(port: Int): Running {
        // The actor system is this backend's alone; nothing outside sees it.
        val server = api().start(port = port, systemName = "greetings-pekko")
        return object : Running {
            override val baseUrl = server.baseUrl
            override fun stop() {
                server.stop().toCompletableFuture().join()
            }
        }
    }
}
