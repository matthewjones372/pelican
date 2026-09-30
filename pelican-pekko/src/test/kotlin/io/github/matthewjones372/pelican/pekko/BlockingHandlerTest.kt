package io.github.matthewjones372.pelican.pekko

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.orFail
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.apache.pekko.actor.testkit.typed.annotations.JUnit5TestKit
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.javadsl.JUnit5TestKitBuilder
import org.apache.pekko.actor.testkit.typed.javadsl.TestKitJUnit5Extension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

data class BlockingRefusal(val reason: String)

/**
 * The synchronous binders run their handler off the dispatcher (spec 0058), so a handler that blocks holds a
 * virtual thread rather than one of the few threads every request is parsed, routed and written on.
 */
@ExtendWith(TestKitJUnit5Extension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlockingHandlerTest {

    /** Two dispatcher threads: on them, sixteen handlers each sleeping half a second take four seconds. */
    @JUnit5TestKit
    @JvmField
    val testKit: ActorTestKit = JUnit5TestKitBuilder()
        .withName("blocking-handler-test")
        .withCustomConfig(
            ConfigFactory.parseString(
                """
                pekko.actor.default-dispatcher.fork-join-executor {
                  parallelism-min = 2
                  parallelism-max = 2
                }
                """.trimIndent(),
            ),
        )
        .build()

    private val slow = endpoint {
        get("slow")
        text()
    }

    private val where = endpoint {
        get("where")
        text()
    }

    private val refused = errorJson<BlockingRefusal>(409, "Refused")

    private val refusing = endpoint {
        get("refusing")
        json<String>() orFail refused
    }

    private val broken = endpoint {
        get("broken")
        text()
    }

    private val api = api(
        endpoints = listOf(
            slow handledNow {
                peak.accumulateAndGet(asleep.incrementAndGet(), ::maxOf)
                try {
                    Thread.sleep(HALF_A_SECOND)
                } finally {
                    asleep.decrementAndGet()
                }
                "done"
            },
            where handledNow { if (Thread.currentThread().isVirtual) "virtual" else "platform" },
            refusing handledOrFail
                { refused(BlockingRefusal(if (Thread.currentThread().isVirtual) "virtual" else "platform")) },
            broken handledNow { error("a bug in the handler") },
        ),
        codecs = JacksonCodecs,
    )

    /** How many slow handlers are asleep at once, and the most there have been. */
    private val asleep = AtomicInteger()
    private val peak = AtomicInteger()

    private val client = HttpClient.newHttpClient()

    private fun get(url: String): CompletableFuture<HttpResponse<String>> =
        client.sendAsync(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun serving(handlers: Handlers = Handlers.onVirtualThreads, block: (String) -> Unit) {
        val server = api.start(testKit.system(), port = 0, handlers = handlers)
        try {
            block(server.baseUrl)
        } finally {
            server.stop()
        }
    }

    /**
     * Counted rather than timed: a wall-clock bound failed on a loaded machine
     * while the handlers were plainly running together. Two dispatcher threads
     * can hold at most two sleeping handlers, so more than two asleep at once
     * is the claim, and the dispatcher test below shows it can fail.
     */
    @Test
    fun `handlers that block do not queue behind each other on the dispatcher`() = serving { base ->
        peak.set(0)
        val answers = (1..CALLS).map { get("$base/slow") }.map { it.join() }

        answers.map { it.body() }.distinct() shouldBe listOf("done")
        withClue("the most slow handlers asleep at once, of $CALLS sent together") {
            peak.get() shouldBeGreaterThan DISPATCHER_THREADS
        }
    }

    @Test
    fun `whereas on the dispatcher no more sleep at once than it has threads`() =
        serving(Handlers.onDispatcher) { base ->
            peak.set(0)
            (1..DISPATCHER_CALLS).map { get("$base/slow") }.forEach { it.join() }

            peak.get() shouldBeLessThanOrEqual DISPATCHER_THREADS
        }

    @Test
    fun `a synchronous handler runs on a virtual thread`() = serving { base ->
        get("$base/where").join().body() shouldBe "virtual"
    }

    @Test
    fun `a declared failure is answered from the virtual thread as declared`() = serving { base ->
        val answer = get("$base/refusing").join()
        answer.statusCode() shouldBe 409
        answer.body() shouldBe """{"reason":"virtual"}"""
    }

    @Test
    fun `a handler that throws is still a 500`() = serving { base ->
        get("$base/broken").join().statusCode() shouldBe 500
    }

    @Test
    fun `on the dispatcher a handler runs where the route matched`() = serving(Handlers.onDispatcher) { base ->
        get("$base/where").join().body() shouldBe "platform"
    }

    private companion object {
        const val CALLS = 16
        const val DISPATCHER_CALLS = 6
        const val DISPATCHER_THREADS = 2
        const val HALF_A_SECOND = 500L
    }
}
