package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jsonBody
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.pekko.actor.testkit.typed.annotations.JUnit5TestKit
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit
import org.apache.pekko.actor.testkit.typed.javadsl.JUnit5TestKitBuilder
import org.apache.pekko.actor.testkit.typed.javadsl.TestKitJUnit5Extension
import org.apache.pekko.http.javadsl.Http
import org.apache.pekko.http.javadsl.ServerBinding
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A body that stops arriving is the caller's problem, and 408 is how RFC 9110
 * says so. `toStrict` gives up after `strictBodyTimeoutMillis` and used to fall
 * through the interpreter's `exceptionally` as an undescribed 500 — so one
 * setting meant a 408 on Ktor and a 500 here.
 */
@ExtendWith(TestKitJUnit5Extension::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlowBodyTest {

    data class Note(val text: String)

    @JUnit5TestKit
    @JvmField
    val testKit: ActorTestKit = JUnit5TestKitBuilder().withName("slow-body-test").build()

    private val note = jsonBody<Note>()

    private val echo = endpoint(note) {
        post("notes")
        text()
    }

    private val api = Api(
        endpoints = listOf(echo handledNow { body -> "read ${body.text.length}" }),
        codecs = JacksonCodecs,
        // Short, so the test waits for a moment rather than ten seconds.
        strictBodyTimeoutMillis = 500,
    )

    private val binding: ServerBinding = Http.get(testKit.system())
        .newServerAt("127.0.0.1", 0)
        .bind(api.toRoute(testKit.system()))
        .toCompletableFuture()
        .join()

    /**
     * Sends a prefix, stalls well past the server's patience, then ends.
     *
     * It has to end. A body that never finishes leaves the client still writing
     * when the answer is written, so the client never gets to read it — which is
     * the same reason the size refusal drains before it answers.
     */
    private fun stalling(): InputStream = object : InputStream() {
        private var sent = false

        override fun read(): Int {
            if (sent) return -1
            sent = true
            Thread.sleep(STALL_MILLIS)
            return '{'.code
        }
    }

    @Test
    fun `a body that stops arriving is a 408, not a 500`() {
        val request =
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${binding.localAddress().port}/notes"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(SECONDS_TO_WAIT))
                .POST(HttpRequest.BodyPublishers.ofInputStream(::stalling))
                .build()

        val res = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

        res.statusCode() shouldBe 408
        withClue(res.body()) { res.body() shouldContain "Timed out reading the request body" }
    }

    private companion object {
        const val STALL_MILLIS = 2_000L
        const val SECONDS_TO_WAIT = 10L
    }
}
