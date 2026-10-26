package io.github.matthewjones372.pelican.gannet.served

import io.github.matthewjones372.gannet.GannetConfig
import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.gannet.Handlers
import io.github.matthewjones372.pelican.gannet.RequiresGannet
import io.github.matthewjones372.pelican.gannet.handledBy
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.gannet.handledOrFail
import io.github.matthewjones372.pelican.gannet.start
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.orFail
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import io.github.matthewjones372.pelican.jetty.Handlers as JettyHandlers
import io.github.matthewjones372.pelican.jetty.handledBy as jettyHandledBy
import io.github.matthewjones372.pelican.jetty.handledNow as jettyHandledNow
import io.github.matthewjones372.pelican.jetty.handledOrFail as jettyHandledOrFail
import io.github.matthewjones372.pelican.jetty.start as jettyStart

/** Spec 0058 on Gannet: a synchronous handler may block, and runs where [Handlers] says, as on Jetty. */
@RequiresGannet
class HandlersRunWhereJettyRunsThemTest {

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

        private val carrier = endpoint {
            get("carrier")
            text()
        }

        private val quick = endpoint {
            get("quick")
            text()
        }

        private val gated = endpoint {
            get("gated")
            text()
        }

        private const val CALLS = 16

        /** Every gated handler waits here until all [CALLS] are inside one at once. */
        private val together = CountDownLatch(CALLS)

        private fun thread(): String = when {
            Thread.currentThread().name.startsWith("mine") -> "mine"
            Thread.currentThread().isVirtual -> "virtual"
            else -> "platform"
        }

        private val gannetApi: Api = api(
            listOf(
                where handledNow { thread() },
                refusing handledOrFail { refused(Refusal(thread())) },
                later handledBy { CompletableFuture.completedFuture(thread()) },
                carrier handledNow { Thread.currentThread().name },
                (quick handledNow { Thread.currentThread().name }).nonBlocking(),
                gated handledNow {
                    together.countDown()
                    if (together.await(GATE_SECONDS, TimeUnit.SECONDS)) "together" else "alone"
                },
            ),
            JacksonCodecs,
        )

        private val jettyRoutes: List<ServerEndpoint> = listOf(
            where jettyHandledNow { thread() },
            refusing jettyHandledOrFail { refused(Refusal(thread())) },
            later jettyHandledBy { CompletableFuture.completedFuture(thread()) },
        )

        private val mine = Executors.newThreadPerTaskExecutor(Thread.ofPlatform().name("mine-", 0).factory())

        private val gannet = mapOf(
            "virtual" to gannetApi.start(port = 0),
            "dispatcher" to gannetApi.start(port = 0, handlers = Handlers.onDispatcher),
            "mine" to gannetApi.start(port = 0, handlers = Handlers.on(mine)),
        )

        private val jetty = mapOf(
            "virtual" to api(jettyRoutes, JacksonCodecs).jettyStart(port = 0),
            "dispatcher" to api(jettyRoutes, JacksonCodecs).jettyStart(port = 0, handlers = JettyHandlers.onDispatcher),
            "mine" to api(jettyRoutes, JacksonCodecs).jettyStart(port = 0, handlers = JettyHandlers.on(mine)),
        )

        private val asking = Asking(listOf("Content-Type"))

        @JvmStatic
        fun cases(): List<Asked> = listOf("virtual", "dispatcher", "mine").flatMap { handlers ->
            listOf(
                Asked("a synchronous handler, $handlers", "/where", handlers),
                Asked("a declared failure, $handlers", "/refusing", handlers),
                Asked("an asynchronous handler is never moved, $handlers", "/later", handlers),
            )
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            asking.close()
            gannet.values.forEach { it.stop() }
            jetty.values.forEach { it.stop() }
            mine.close()
        }

        private const val GATE_SECONDS = 30L
    }

    private val client = HttpClient.newHttpClient()

    private fun get(url: String): CompletableFuture<HttpResponse<String>> =
        client.sendAsync(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a handler runs where jetty would run it`(asked: Asked) {
        val onGannet = asking.ask(gannet.getValue(asked.handlers).baseUrl, "GET", asked.path)
        val onJetty = asking.ask(jetty.getValue(asked.handlers).baseUrl, "GET", asked.path)
        withClue("${asked.name}: gannet $onGannet, jetty $onJetty") { onGannet shouldBe onJetty }
    }

    @Test
    fun `on the dispatcher a synchronous handler runs on the carrier that read its request`() {
        get("${gannet.getValue("dispatcher").baseUrl}/carrier").join().body() shouldStartWith "gannet-"
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["virtual", "mine"])
    fun `a non-blocking handler runs on the carrier that read its request, whatever the handlers`(handlers: String) {
        get("${gannet.getValue(handlers).baseUrl}/quick").join().body() shouldStartWith "gannet-"
    }

    @Test
    fun `handlers that block all run at once, on one carrier`() {
        val server = gannetApi.start(port = 0, config = GannetConfig(carriers = 1))
        try {
            val answers = (1..CALLS).map { get("${server.baseUrl}/gated") }.map { it.join().body() }
            answers.distinct() shouldBe listOf("together")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `by default a handler runs on a virtual thread`() {
        get("${gannet.getValue("virtual").baseUrl}/where").join().body() shouldBe "virtual"
    }
}
