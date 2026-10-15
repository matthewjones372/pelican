package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.Api
import org.eclipse.jetty.http.UriCompliance
import org.eclipse.jetty.http.UriCompliance.Violation.AMBIGUOUS_PATH_ENCODING
import org.eclipse.jetty.http.UriCompliance.Violation.AMBIGUOUS_PATH_SEGMENT
import org.eclipse.jetty.http.UriCompliance.Violation.AMBIGUOUS_PATH_SEPARATOR
import org.eclipse.jetty.http.UriCompliance.Violation.SUSPICIOUS_PATH_CHARACTERS
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.NetworkConnector
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.VirtualThreads
import org.eclipse.jetty.util.thread.QueuedThreadPool
import org.eclipse.jetty.util.thread.ThreadPool
import org.eclipse.jetty.util.thread.VirtualThreadPool
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A bound server, and the handle to shut it down again. */
class PelicanServer internal constructor(
    /** The API this server is serving. A test client needs its codecs. */
    val api: Api,
    val server: Server,
    /** The interface this server was bound to, which defaults to loopback. */
    val host: String,
) : AutoCloseable {
    private val stopped = CountDownLatch(1)

    val port: Int get() = (server.connectors.single() as NetworkConnector).localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    /** [stopAsync], waited on: the blocking spelling every backend shares. */
    fun stop() {
        stopAsync().toCompletableFuture().get(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
    }

    /** Stops Jetty, which closes the port and its threads; the stage completes once it has. */
    fun stopAsync(): CompletionStage<Unit> =
        CompletableFuture.runAsync { server.stop() }.thenApply { stopped.countDown() }

    /** Parks the calling thread until [stop]: what a `main` wants. */
    fun block() {
        stopped.await()
    }

    override fun close() = stop()
}

/** Long enough that a real shutdown never hits it, short enough that a stuck one fails a build. */
private val STOP_TIMEOUT: Duration = Duration.ofSeconds(30)

/**
 * Jetty's default, less four refusals Pekko does not make. Each is safe because Pelican routes on the encoded
 * path, resolves dot segments first, then splits and decodes each segment once:
 * - `AMBIGUOUS_PATH_SEGMENT` (`%2E%2E`): resolved as `..` before routing, so it never reaches a handler as one.
 * - `AMBIGUOUS_PATH_SEPARATOR` (`%2F`): decoded after the split, so it is a character inside one value.
 * - `AMBIGUOUS_PATH_ENCODING` (`%25`): decoded once, so `%2561` arrives as the text `%61`.
 * - `SUSPICIOUS_PATH_CHARACTERS` (`%5C`): a character inside one value; nothing here reads it as a separator.
 *
 * A value can therefore hold `/` or `\`, as it can on Pekko: a handler using one as a file name must check it.
 * A raw `..` climbing above the root stays Jetty's own 400, which no setting lifts.
 */
val PELICAN_URI_COMPLIANCE: UriCompliance = UriCompliance.DEFAULT.with(
    "pelican",
    AMBIGUOUS_PATH_SEGMENT,
    AMBIGUOUS_PATH_SEPARATOR,
    AMBIGUOUS_PATH_ENCODING,
    SUSPICIOUS_PATH_CHARACTERS,
)

/**
 * Binds this API on [host]:[port]; port 0 lets the OS choose. [handler] is how a module knowing more than this
 * one, such as one serving an OpenAPI document, adds to it; a request it passes on is answered by [Unanswered].
 */
fun Api.start(
    port: Int = 8080,
    host: String = "127.0.0.1",
    handlers: Handlers = Handlers.default,
    handler: Api.() -> Handler = { toHandler(handlers) },
): PelicanServer {
    val server = Server(pelicanPool(handlers))
    server.addConnector(
        pelicanConnector(server, ConnectorSettings.configured()).apply {
            this.host = host
            this.port = port
        },
    )
    server.handler = handler()
    server.defaultHandler = Unanswered()
    try {
        server.start()
    } catch (t: Throwable) {
        server.stop()
        throw t
    }
    return PelicanServer(this, server, host)
}

/**
 * Jetty's own pool, calling handlers on virtual threads when [handlers] runs them on one anyway, so a synchronous
 * handler starts on a virtual thread rather than hopping to one. Any other choice keeps Jetty's platform threads,
 * which is where [Handlers.onDispatcher] promises a handler runs.
 */
internal fun pelicanThreads(handlers: Handlers, maxThreads: Int = DEFAULT_MAX_THREADS): QueuedThreadPool =
    QueuedThreadPool(maxThreads).apply {
        if (handlers.virtual) virtualThreadsExecutor = VirtualThreads.getNamedVirtualThreadsExecutor("pelican-")
    }

/** Jetty's own default. */
private const val DEFAULT_MAX_THREADS = 200

/** The pools [start] can build, chosen by the `pelican.jetty.pool` system property so one build can measure both. */
internal enum class JettyPool {
    /** [pelicanThreads]: Jetty's own work on platform threads, handlers on virtual ones where [Handlers] says. */
    QUEUED,

    /** Jetty's [VirtualThreadPool]: everything, selectors and acceptors included, on virtual threads. */
    VIRTUAL,
    ;

    companion object {
        fun configured(): JettyPool = System.getProperty(POOL_PROPERTY)?.let { valueOf(it.uppercase()) } ?: QUEUED
    }
}

internal const val POOL_PROPERTY = "pelican.jetty.pool"

/**
 * [pool], where [handlers] runs handlers on virtual threads anyway. [Handlers.onDispatcher] and [Handlers.on] keep
 * [pelicanThreads], since they promise a handler a Jetty platform thread or a hop to their own executor.
 */
internal fun pelicanPool(handlers: Handlers, pool: JettyPool = JettyPool.configured()): ThreadPool =
    if (pool == JettyPool.VIRTUAL && handlers.virtual) {
        // Unbounded, as the virtual-threads executor under [pelicanThreads] is, so the two differ only in the pool.
        VirtualThreadPool(UNBOUNDED).apply { name = "pelican-" }
    } else {
        pelicanThreads(handlers)
    }

/** [VirtualThreadPool] takes no semaphore at zero. */
private const val UNBOUNDED = 0

/** [start]'s connector, with [settings] applied over Jetty's defaults. */
internal fun pelicanConnector(server: Server, settings: ConnectorSettings): ServerConnector {
    val http = HttpConfiguration().apply {
        uriCompliance = PELICAN_URI_COMPLIANCE
        settings.applyTo(this)
    }
    val factory = HttpConnectionFactory(http).apply { settings.applyTo(this) }
    return ServerConnector(server, settings.acceptors, settings.selectors, factory)
}

/**
 * Connector settings for measuring one against another (spec 0085), read by [start] from the
 * `pelican.jetty.connector` system property as `key=value` pairs separated by commas. A key left out keeps
 * Jetty's default, and an unknown key fails, so a typo cannot pass for a measurement of the default.
 */
internal class ConnectorSettings private constructor(private val values: Map<String, String>) {
    val acceptors: Int = values["acceptors"]?.toInt() ?: JETTY_CHOOSES
    val selectors: Int = values["selectors"]?.toInt() ?: JETTY_CHOOSES

    fun applyTo(http: HttpConfiguration) {
        // The buffer first: setting it resets the aggregation size to a quarter of it.
        values["outputBufferSize"]?.let { http.outputBufferSize = it.toInt() }
        values["outputAggregationSize"]?.let { http.outputAggregationSize = it.toInt() }
        values["sendDateHeader"]?.let { http.sendDateHeader = it.toBooleanStrict() }
        values["sendServerVersion"]?.let { http.sendServerVersion = it.toBooleanStrict() }
    }

    fun applyTo(factory: HttpConnectionFactory) {
        values["inputBufferSize"]?.let { factory.inputBufferSize = it.toInt() }
    }

    companion object {
        private val KEYS = setOf(
            "acceptors",
            "selectors",
            "outputBufferSize",
            "outputAggregationSize",
            "inputBufferSize",
            "sendDateHeader",
            "sendServerVersion",
        )

        fun parse(text: String): ConnectorSettings = ConnectorSettings(
            text.split(',').filter { it.isNotBlank() }.associate { pair ->
                val parts = pair.split('=', limit = 2).map { it.trim() }
                require(parts.size == 2) { "$CONNECTOR_PROPERTY wants key=value, not '$pair'" }
                require(parts[0] in KEYS) { "$CONNECTOR_PROPERTY has no '${parts[0]}'; it knows $KEYS" }
                parts[0] to parts[1]
            },
        )

        fun configured(): ConnectorSettings = parse(System.getProperty(CONNECTOR_PROPERTY).orEmpty())
    }
}

internal const val CONNECTOR_PROPERTY = "pelican.jetty.connector"

/** What [ServerConnector] takes to mean: size it from the machine's cores. */
private const val JETTY_CHOOSES = -1
