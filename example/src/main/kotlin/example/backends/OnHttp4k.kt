package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.http4k.handledNow
import io.github.matthewjones372.pelican.http4k.handledOneOf
import io.github.matthewjones372.pelican.http4k.handledOrFail
import io.github.matthewjones372.pelican.http4k.start
import io.github.matthewjones372.pelican.http4k.streamedNow

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

    remember handledOneOf { (who, note) -> rememberGreeting(who, note) },

    preferences handledNow { (locale, session) -> preferencesOf(locale, session) },

    signIn handledNow { form -> sessionOf(form) },

    uploadFile handledNow { (caption, notes, file) -> uploaded(caption, notes, file) },

    filters handledNow { (tags, ids, features, seen) -> filtersOf(tags, ids, features, seen) },

    strict handledNow { (term, key, jar) -> Strictly(term, key, jar) },
)

fun http4kApi(outerFilters: List<Filter> = emptyList()): Api =
    greetingsApi(http4kRoutes, outerFilters = outerFilters)

object OnHttp4k : Backend {
    override val name = "http4k"

    override fun api(outerFilters: List<Filter>): Api = http4kApi(outerFilters)

    override fun start(port: Int, outerFilters: List<Filter>): Running {
        val server = api(outerFilters).start(port = port)
        return object : Running {
            override val baseUrl = server.baseUrl
            override fun stop() = server.stop()
        }
    }
}
