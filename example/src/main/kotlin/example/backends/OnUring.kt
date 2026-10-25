package example.backends

/*
 * To run this in a project of your own:
 *
 *     dependencies {
 *         // the interpreter and the io_uring runtime under it; brings pelican-core
 *         implementation("io.github.matthewjones372:pelican-uring:1.0.0")
 *     }
 */

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiErrorEnvelope
import io.github.matthewjones372.pelican.Filter
import io.github.matthewjones372.pelican.RefusalObserver
import io.github.matthewjones372.pelican.RefusalRenderer
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.lastEventId
import io.github.matthewjones372.pelican.gannet.bytesNow
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.handledOneOf
import io.github.matthewjones372.pelican.gannet.handledOrFail
import io.github.matthewjones372.pelican.gannet.handledWith
import io.github.matthewjones372.pelican.gannet.start
import io.github.matthewjones372.pelican.gannet.streamedNow
import io.github.matthewjones372.pelican.gannet.toSequence

/**
 * The io_uring binding of [greetingEndpoints].
 *
 * [jettyRoutes] again under another import: the binders have the same names and the stream type is the same
 * `Sequence`. Handlers run on virtual threads, off the carrier that read the request, so a sleep or a blocking
 * read of an upload holds up no other connection. The JVM needs the flags `UringSupport.FLAGS` names.
 */
val uringRoutes: List<ServerEndpoint> = listOf(
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

fun uringApi(
    outerFilters: List<Filter> = emptyList(),
    refusals: RefusalRenderer = ApiErrorEnvelope,
    onRefusal: RefusalObserver? = null,
): Api = greetingsApi(uringRoutes, outerFilters = outerFilters, refusals = refusals, onRefusal = onRefusal)

object OnUring : Backend {
    override val name = "uring"

    override fun api(outerFilters: List<Filter>, refusals: RefusalRenderer, onRefusal: RefusalObserver?): Api =
        uringApi(outerFilters, refusals, onRefusal)

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
