package example.backends

import dev.pelican.Api
import dev.pelican.ServerEndpoint
import dev.pelican.pekko.handledNow
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

    echo handledNow { (trace, note) -> echoed(trace, note) },

    preferences handledNow { (locale, session) -> preferencesOf(locale, session) },

    signIn handledNow { form -> sessionOf(form) },

    uploadFile handledNow { (caption, file) -> uploaded(caption, file) },

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
