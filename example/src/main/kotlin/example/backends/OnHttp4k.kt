package example.backends

import dev.pelican.Api
import dev.pelican.ServerEndpoint
import dev.pelican.http4k.handledNow
import dev.pelican.http4k.handledOneOf
import dev.pelican.http4k.handledOrFail
import dev.pelican.http4k.start
import dev.pelican.http4k.streamedNow

/**
 * The http4k binding of the same [greetingEndpoints].
 *
 * Same two endpoint values, same handler logic, same `Api` settings. The one
 * difference is the stream type: `streamedNow` here takes `(I) -> Sequence<T>`,
 * pulled one element at a time as the response body is written, because http4k
 * answers on the calling thread rather than handing a stream to a server that
 * will drain it.
 *
 * `Thread.sleep` in the sequence is therefore the honest equivalent of Pekko's
 * `throttle`: it delays the *next* row rather than the whole response.
 */
val http4kRoutes: List<ServerEndpoint> = listOf(
    greet handledNow { (who, shout) -> greetingOf(who, shout) },

    countdown streamedNow { start ->
        (start downTo 1).asSequence().map { seq ->
            Thread.sleep(100)
            tick(seq)
        }
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

    uploadFile handledNow { (caption, file) -> uploaded(caption, file) },

    filters handledNow { (tags, ids, features, seen) -> filtersOf(tags, ids, features, seen) },
)

fun http4kApi(): Api = greetingsApi(http4kRoutes)

object OnHttp4k : Backend {
    override val name = "http4k"

    override fun api(): Api = http4kApi()

    override fun start(port: Int): Running {
        val server = api().start(port = port)
        return object : Running {
            override val baseUrl = server.baseUrl
            override fun stop() = server.stop()
        }
    }
}
