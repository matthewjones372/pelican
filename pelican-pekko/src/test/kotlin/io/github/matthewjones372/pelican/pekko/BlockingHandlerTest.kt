package io.github.matthewjones372.pelican.pekko

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.util.concurrent.TimeUnit

/** Over a socket, because what is at stake is the server's threads rather than what a route answers. */
@ExtendWith(TestKitJUnit5Extension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlockingHandlerTest {

    /** Two dispatcher threads, so a blocking handler on either is felt at once. */
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
                """,
            ),
        )
        .build()

    data class Problem(val message: String)

    private val sleep = endpoint {
        get("sleep")
        text()
    }

    private val where = endpoint {
        get("where")
        text() orFail errorJson<Problem>(409, "Never")
    }

    private val thread = endpoint {
        get("thread")
        text()
    }

    private val boom = endpoint {
        get("boom")
        text()
    }

    private val api = api(
        endpoints = listOf(
            sleep handledNow {
                Thread.sleep(SLEEP_MILLIS)
                "slept"
            },
            where handledOrFail { ok(Thread.currentThread().isVirtual.toString()) },
            thread handledNow { Thread.currentThread().name },
            boom handledNow { error("a bug") },
        ),
        codecs = JacksonCodecs,
    )

    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    private fun get(url: String): CompletableFuture<HttpResponse<String>> =
        client.sendAsync(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `sixty-four handlers sleeping a second each answer inside two seconds on two dispatcher threads`() {
        api.start(testKit.system(), port = 0).use { server ->
            // Class loading and the first connection are not what is being timed.
            get("${server.baseUrl}/where").join()
            val started = System.nanoTime()
            val answers = (1..CONCURRENT).map { get("${server.baseUrl}/sleep") }
            CompletableFuture.allOf(*answers.toTypedArray()).get(WAIT_SECONDS, TimeUnit.SECONDS)
            val tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            answers.map { it.join().body() }.distinct() shouldBe listOf("slept")
            withClue("$CONCURRENT one-second handlers took ${tookMillis}ms") {
                tookMillis shouldBeLessThan 2_000L
            }
        }
    }

    @Test
    fun `a synchronous handler runs on a virtual thread`() {
        api.start(testKit.system(), port = 0).use { server ->
            get("${server.baseUrl}/where").join().body() shouldBe "true"
        }
    }

    @Test
    fun `a handler on the dispatcher runs on the thread that matched the route`() {
        api.start(testKit.system(), port = 0, handlers = Handlers.onDispatcher).use { server ->
            get("${server.baseUrl}/where").join().body() shouldBe "false"
            get("${server.baseUrl}/thread").join().body() shouldContain "pekko.actor.default-dispatcher"
        }
    }

    @Test
    fun `a synchronous handler that throws still answers 500`() {
        api.start(testKit.system(), port = 0).use { server ->
            get("${server.baseUrl}/boom").join().statusCode() shouldBe 500
        }
    }

    private companion object {
        const val CONCURRENT = 64
        const val SLEEP_MILLIS = 1_000L

        /** Past the claim, so a failure reports the time taken rather than a timeout. */
        const val WAIT_SECONDS = 60L
    }
}
