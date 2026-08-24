package example.backends

import dev.pelican.Api
import dev.pelican.ServerEndpoint
import dev.pelican.ktor.handledNow
import dev.pelican.ktor.handledOrFail
import dev.pelican.ktor.start
import dev.pelican.ktor.streamedNow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

/**
 * The Ktor binding of the same [greetingEndpoints].
 *
 * Two differences from the other two files, both of them Ktor's own calling
 * convention rather than anything Pelican adds.
 *
 * The handlers suspend: `handledNow` here takes `suspend (I) -> T`, so a
 * handler may await whatever it likes — a database, another service — and a
 * lambda that awaits nothing, like the one below, still fits.
 *
 * And a stream is a `Flow<T>`, collected as the response body is written. That
 * makes `delay(100)` the exact equivalent of Pekko's `throttle` and http4k's
 * `Thread.sleep`: it holds up the next row without parking a thread.
 */
val ktorRoutes: List<ServerEndpoint> = listOf(
    greet handledNow { (who, shout) -> greetingOf(who, shout) },

    countdown streamedNow { start ->
        flow {
            for (seq in start downTo 1) {
                delay(100)
                emit(tick(seq))
            }
        }
    },

    // The one endpoint here that declares a failure, so this is the binder
    // that demands an `Outcome` — and the 429 it may answer with carries a
    // `Retry-After`, on all three backends, from the one description.
    echo handledOrFail { (trace, note) -> echoOrRefuse(trace, note) },

    preferences handledNow { (locale, session) -> preferencesOf(locale, session) },

    signIn handledNow { form -> sessionOf(form) },

    uploadFile handledNow { (caption, file) -> uploaded(caption, file) },

    filters handledNow { (tags, ids, features, seen) -> filtersOf(tags, ids, features, seen) },
)

fun ktorApi(): Api = greetingsApi(ktorRoutes)

object OnKtor : Backend {
    override val name = "ktor"

    override fun api(): Api = ktorApi()

    override fun start(port: Int): Running {
        val server = api().start(port = port)
        return object : Running {
            override val baseUrl = server.baseUrl
            override fun stop() = server.stop()
        }
    }
}
