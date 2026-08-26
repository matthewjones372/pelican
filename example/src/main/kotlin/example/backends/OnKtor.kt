package example.backends

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiErrorEnvelope
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.RefusalObserver
import io.github.matthewjones372.pelican.RefusalRenderer
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.ktor.bytesNow
import io.github.matthewjones372.pelican.ktor.handledNow
import io.github.matthewjones372.pelican.ktor.handledOneOf
import io.github.matthewjones372.pelican.ktor.handledOrFail
import io.github.matthewjones372.pelican.ktor.handledWith
import io.github.matthewjones372.pelican.ktor.start
import io.github.matthewjones372.pelican.ktor.streamedNow
import io.github.matthewjones372.pelican.ktor.toChannel
import io.github.matthewjones372.pelican.ktor.toFlow
import io.github.matthewjones372.pelican.lastEventId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.flow

/**
 * The Ktor binding of the same [greetingEndpoints].
 *
 * Two differences from the other two files, both of them Ktor's own calling
 * convention rather than anything Pelican adds.
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

    remember handledOneOf { (who, note) -> rememberGreeting(who, note) },

    preferences handledNow { (locale, session) -> preferencesOf(locale, session) },

    signIn handledNow { form -> sessionOf(form) },

    uploadFile handledNow { (caption, notes, file) -> uploaded(caption, notes, file) },

    filters handledNow { (tags, ids, features, seen) -> filtersOf(tags, ids, features, seen) },

    strict handledNow { (term, key, jar) -> Strictly(term, key, jar) },

    roundtrip handledNow { (inPath, inQuery) -> RoundTrip(inPath, inQuery) },

    motd handledNow { "Be excellent to each other." },

    forget handledWith { _ -> },

    peek handledWith { _ -> },

    ticker streamedNow { ticks().asFlow() },
    replay streamedNow { ticksSince(lastEventId()).asFlow() },
    everyone streamedNow { greetingsOf().asFlow() },

    logo bytesNow { io.ktor.utils.io.ByteReadChannel(LOGO_BYTES) },

    echoRaw bytesNow { body -> body.toChannel() },

    // The same count, off a cold `Flow`: collecting it reads the channel a
    // chunk at a time, so the upload is never held whole.
    tally handledNow { rows -> Tally(rows.toFlow().count()) },
)

fun ktorApi(
    outerFilters: List<Filter> = emptyList(),
    refusals: RefusalRenderer = ApiErrorEnvelope,
    onRefusal: RefusalObserver? = null,
): Api = greetingsApi(ktorRoutes, outerFilters = outerFilters, refusals = refusals, onRefusal = onRefusal)

object OnKtor : Backend {
    override val name = "ktor"

    override fun api(outerFilters: List<Filter>, refusals: RefusalRenderer, onRefusal: RefusalObserver?): Api =
        ktorApi(outerFilters, refusals, onRefusal)

    override fun start(
        port: Int,
        outerFilters: List<Filter>,
        refusals: RefusalRenderer,
        onRefusal: RefusalObserver?,
    ): Running {
        val server = api(outerFilters, refusals, onRefusal).start(port = port)
        return object : Running {
            override val baseUrl = server.baseUrl
            override fun stop() = server.stop()
        }
    }
}
