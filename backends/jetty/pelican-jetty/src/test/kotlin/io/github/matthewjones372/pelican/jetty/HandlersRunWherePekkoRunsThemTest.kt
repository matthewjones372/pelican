package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.orFail
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.HttpConfiguration
import org.eclipse.jetty.server.HttpConnectionFactory
import org.eclipse.jetty.server.NetworkConnector
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.util.Callback
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import io.github.matthewjones372.pelican.pekko.Handlers as PekkoHandlers
import io.github.matthewjones372.pelican.pekko.handledBy as pekkoHandledBy
import io.github.matthewjones372.pelican.pekko.handledNow as pekkoHandledNow
import io.github.matthewjones372.pelican.pekko.handledOrFail as pekkoHandledOrFail
import io.github.matthewjones372.pelican.pekko.start as pekkoStart

/** Spec 0058 on Jetty: a synchronous handler may block, and runs where [Handlers] says, as on Pekko. */
class HandlersRunWherePekkoRunsThemTest {

    data class Refusal(val reason: String)

    /** One request, named so a failing case says which. */
    data class Asked(val name: String, val path: String, val handlers: String) {
        override fun toString() = name
    }

    companion object {
        private val where = endpoint {
            get("where")
            text()
        }

        private val refused = errorJson<Refusal>(409, "Refused")

        private val refusing = endpoint {
            get("refusing")
            json<String>() orFail refused
        }

        private val later = endpoint {
            get("later")
            text()
        }

        private val gated = endpoint {
            get("gated")
            text()
        }

        private val slow = endpoint {
            get("slow")
            text()
        }

        private val same = endpoint {
            get("same")
            text()
        }

        /** Which kind of thread runs a handler, naming Pelican's platform pool. */
        private fun pool(): String = when {
            Thread.currentThread().isVirtual -> "virtual"
            Thread.currentThread().name.startsWith("pelican-handler-") -> "pelican-handler"
            else -> "platform"
        }

        /** What [Handlers.default] runs a handler on, on the JDK running the suite. */
        private fun byDefault(): String =
            if (Runtime.version().feature() >= UNPINNED_FROM) "virtual" else "pelican-handler"

        private const val UNPINNED_FROM = 24

        private const val CALLS = 16
        private const val DISPATCHER_CALLS = 6

        /** Every gated handler waits here until all [CALLS] are inside one at once. */
        private val together = CountDownLatch(CALLS)
        private val asleep = AtomicInteger()
        private val peak = AtomicInteger()

        private const val ASKED_ON = "io.github.matthewjones372.pelican.jetty.test.asked-on"

        private fun thread(): String = when {
            Thread.currentThread().name.startsWith("mine") -> "mine"
            Thread.currentThread().isVirtual -> "virtual"
            else -> "platform"
        }

        private val jettyApi: Api = api(
            listOf(
                where handledNow { thread() },
                refusing handledOrFail { refused(Refusal(thread())) },
                later handledBy { CompletableFuture.completedFuture(thread()) },
                gated handledNow {
                    together.countDown()
                    if (together.await(GATE_SECONDS, TimeUnit.SECONDS)) "together" else "alone"
                },
                slow handledNow {
                    peak.accumulateAndGet(asleep.incrementAndGet(), ::maxOf)
                    try {
                        Thread.sleep(HALF_A_SECOND)
                    } finally {
                        asleep.decrementAndGet()
                    }
                    "done"
                },
                same handledNow { if (request.getAttribute(ASKED_ON) === Thread.currentThread()) "same" else "moved" },
            ),
            JacksonCodecs,
        )

        private val pekkoRoutes: List<ServerEndpoint> = listOf(
            where pekkoHandledNow { thread() },
            refusing pekkoHandledOrFail { refused(Refusal(thread())) },
            later pekkoHandledBy { CompletableFuture.completedFuture(thread()) },
        )

        private val mine = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().name("mine-", 0).factory())

        private val jetty = mapOf(
            "virtual" to jettyApi.start(port = 0),
            "dispatcher" to jettyApi.start(port = 0, handlers = Handlers.onDispatcher),
            "mine" to jettyApi.start(port = 0, handlers = Handlers.on(mine)),
        )

        private val pekko = mapOf(
            "virtual" to api(pekkoRoutes, JacksonCodecs).pekkoStart(port = 0),
            "dispatcher" to api(pekkoRoutes, JacksonCodecs).pekkoStart(port = 0, handlers = PekkoHandlers.onDispatcher),
            "mine" to api(pekkoRoutes, JacksonCodecs).pekkoStart(port = 0, handlers = PekkoHandlers.on(mine)),
        )

        private val asking = Asking(listOf("Content-Type"))

        @JvmStatic
        fun cases(): List<Asked> = listOf("virtual", "dispatcher", "mine").flatMap { handlers ->
            listOf(
                Asked("a synchronous handler, $handlers", "/where", handlers),
                Asked("a declared failure, $handlers", "/refusing", handlers),
            )
        } + listOf("dispatcher", "mine").map { Asked("an asynchronous handler is never moved, $it", "/later", it) }

        @JvmStatic
        @AfterAll
        fun stop() {
            asking.close()
            jetty.values.forEach { it.stop() }
            pekko.values.forEach { it.stop() }
            mine.close()
        }

        private const val GATE_SECONDS = 30L
        private const val HALF_A_SECOND = 500L

        /** Four threads, two of them the connector's acceptor and selector: two are left to run handlers. */
        private const val POOL_THREADS = 4
        private const val HANDLER_THREADS = 2
        private const val PLATFORM_THREADS = 8
    }

    private val client = HttpClient.newHttpClient()

    private fun get(url: String): CompletableFuture<HttpResponse<String>> =
        client.sendAsync(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

    /** [handler] on a server of its own, whose pool calls handlers on virtual threads only where [handlers] would. */
    private fun serving(handlers: Handlers, maxThreads: Int, handler: Handler, block: (String) -> Unit) {
        val server = Server(pelicanThreads(handlers, maxThreads).apply { reservedThreads = 0 })
        val http = HttpConfiguration().apply { uriCompliance = PELICAN_URI_COMPLIANCE }
        // One acceptor and one selector, so the pool's other threads are the ones left for handlers.
        server.addConnector(ServerConnector(server, 1, 1, HttpConnectionFactory(http)))
        server.handler = handler
        server.defaultHandler = Unanswered()
        server.start()
        try {
            block("http://127.0.0.1:${(server.connectors.single() as NetworkConnector).localPort}")
        } finally {
            server.stop()
        }
    }

    private fun onSmallPool(handlers: Handlers, block: (String) -> Unit) =
        serving(handlers, POOL_THREADS, jettyApi.toHandler(handlers), block)

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a handler runs where pekko would run it`(asked: Asked) {
        val onJetty = asking.ask(jetty.getValue(asked.handlers).baseUrl, "GET", asked.path)
        val onPekko = asking.ask(pekko.getValue(asked.handlers).baseUrl, "GET", asked.path)
        withClue("${asked.name}: jetty $onJetty, pekko $onPekko") { onJetty shouldBe onPekko }
    }

    @Test
    fun `handlers that block all run at once, on a pool with two threads to spare`() =
        onSmallPool(Handlers.onVirtualThreads) { base ->
            val answers = (1..CALLS).map { get("$base/gated") }.map { it.join().body() }
            answers.distinct() shouldBe listOf("together")
        }

    @Test
    fun `whereas on the dispatcher no more run at once than the pool has threads for them`() =
        onSmallPool(Handlers.onDispatcher) { base ->
            peak.set(0)
            (1..DISPATCHER_CALLS).map { get("$base/slow") }.map { it.join().body() }.distinct() shouldBe listOf("done")
            peak.get() shouldBeLessThanOrEqual HANDLER_THREADS
        }

    @Test
    fun `started, a synchronous handler runs on the virtual thread jetty called, without a hop`() {
        val server = jettyApi.start(port = 0, handlers = Handlers.onVirtualThreads) {
            recordingThread(toHandler(Handlers.onVirtualThreads))
        }
        try {
            get("${server.baseUrl}/same").join().body() shouldBe "same"
            get("${server.baseUrl}/where").join().body() shouldBe "virtual"
        } finally {
            server.stop()
        }
    }

    @Test
    fun `mounted on platform threads, a synchronous handler hops to a virtual one`() =
        serving(
            Handlers.onDispatcher,
            PLATFORM_THREADS,
            recordingThread(jettyApi.toHandler(Handlers.onVirtualThreads)),
        ) { base ->
            get("$base/same").join().body() shouldBe "moved"
            get("$base/where").join().body() shouldBe "virtual"
        }

    @Test
    fun `by default a handler runs on a virtual thread from JDK 24, and on pelican's platform pool below it`() {
        val server = api(listOf(where handledNow { pool() }), JacksonCodecs).start(port = 0)
        try {
            get("${server.baseUrl}/where").join().body() shouldBe byDefault()
        } finally {
            server.stop()
        }
    }

    /** [handler], told which thread Jetty asked it on. */
    private fun recordingThread(handler: Handler): Handler = object : Handler.Wrapper(handler) {
        override fun handle(request: Request, response: Response, callback: Callback): Boolean {
            request.setAttribute(ASKED_ON, Thread.currentThread())
            return super.handle(request, response, callback)
        }
    }
}
