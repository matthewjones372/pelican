package example.backends

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // the interpreter; brings pelican-core and Jetty's server
 *         implementation("io.github.matthewjones372:pelican-jetty:1.0.0")
 *     }
 */

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiErrorEnvelope
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.RefusalObserver
import io.github.matthewjones372.pelican.RefusalRenderer
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.jetty.bytesNow
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.jetty.handledOneOf
import io.github.matthewjones372.pelican.jetty.handledOrFail
import io.github.matthewjones372.pelican.jetty.handledWith
import io.github.matthewjones372.pelican.jetty.start
import io.github.matthewjones372.pelican.jetty.streamedNow
import io.github.matthewjones372.pelican.jetty.toSequence
import io.github.matthewjones372.pelican.lastEventId

/**
 * The Jetty binding of [greetingEndpoints].
 *
 * The handler logic is [pekkoRoutes]' own; the stream type is a `Sequence`, pulled one element per write, so
 * `Thread.sleep` delays the next row as Pekko's `throttle` does. Handlers run on virtual threads, where a sleep
 * or a blocking read of an upload costs no platform thread.
 */
val jettyRoutes: List<ServerEndpoint> = listOf(
    greet handledNow { (who, shout) -> greetingOf(who, shout) },

    countdown streamedNow { start ->
        (start downTo 1).asSequence().mapIndexed { i, seq ->
            if (i > 0) Thread.sleep(COUNTDOWN_MILLIS)
            tick(seq)
        }
    },

    echo handledOrFail { (trace, note) -> echoOrRefuse(trace, note) },

    remember handledOneOf { (who, note) -> rememberGreeting(who, note) },

    preferences handledNow { (locale, session) -> preferencesOf(locale, session) },

    signIn handledNow { form -> sessionOf(form) },

    uploadFile handledNow { (caption, notes, file) -> uploaded(caption, notes, file) },

    filters handledNow { (tags, ids, features, seen) -> filtersOf(tags, ids, features, seen) },

    strict handledNow { (term, key, jar) -> Strictly(term, key, jar) },

    roundtrip handledNow { (inPath, inQuery) -> RoundTrip(inPath, inQuery) },

    motd handledNow { "Be excellent to each other." },

    leaveTip handledNow { tip -> TipJar.tipped(tip) },

    refundTip handledNow { tip -> Receipt(number = 0, amount = -tip.amount) },

    forget handledWith { _ -> },

    peek handledWith { _ -> },

    ticker streamedNow { ticks().asSequence() },
    replay streamedNow { ticksSince(lastEventId()).asSequence() },
    everyone streamedNow { greetingsOf().asSequence() },

    logo bytesNow { sequenceOf(LOGO_BYTES) },

    echoRaw bytesNow { body -> body.toSequence() },

    tally handledNow { rows -> Tally(rows.toSequence().count()) },
)

private const val COUNTDOWN_MILLIS = 100L

fun jettyApi(
    outerFilters: List<Filter> = emptyList(),
    refusals: RefusalRenderer = ApiErrorEnvelope,
    onRefusal: RefusalObserver? = null,
): Api = greetingsApi(jettyRoutes, outerFilters = outerFilters, refusals = refusals, onRefusal = onRefusal)

object OnJetty : Backend {
    override val name = "jetty"

    override fun api(outerFilters: List<Filter>, refusals: RefusalRenderer, onRefusal: RefusalObserver?): Api =
        jettyApi(outerFilters, refusals, onRefusal)

    override fun start(
        port: Int,
        outerFilters: List<Filter>,
        refusals: RefusalRenderer,
        onRefusal: RefusalObserver?,
    ): Running {
        val server = api(outerFilters, refusals, onRefusal).start(port = port)
        return object : Running {
            override val baseUrl = server.baseUrl
            override fun stop() {
                server.stop()
            }
        }
    }
}
