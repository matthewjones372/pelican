package dev.pelican.pekko

import dev.pelican.Api
import dev.pelican.endpoint
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.jsonBody
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Behaviors
import org.apache.pekko.http.javadsl.Http
import org.apache.pekko.http.javadsl.ServerBinding
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A body with no declared length is still refused when it is too big.
 *
 * The interpreter reads a strict body two ways. A request that declares a
 * Content-Length has that number checked before anything is read, and is then
 * read with Pekko's plain `toStrict` — the size-limited variant materialises a
 * limiting stage in front of the entity, which measured at about six
 * microseconds a request, and there is nothing for it to catch once the
 * declared length has been refused.
 *
 * A chunked request declares nothing, so it gets the limiting stage, and that
 * is the only thing standing between the service and an unbounded read. This
 * test is what stops the two branches being collapsed back together by
 * somebody who reads the fast one and assumes it is the whole story.
 *
 * `BodyPublishers.ofInputStream` is how the JDK client is made to send chunked
 * with no Content-Length: it cannot know the length in advance either.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChunkedBodyLimitTest {

    data class Note(val text: String)

    private val note = jsonBody<Note>()

    private val echo = endpoint(note) {
        post("notes")
        text()
    }

    @Suppress("ForbiddenVoid") // Pekko's Java DSL; see config/detekt/detekt.yml.
    private val system = ActorSystem.create(Behaviors.empty<Void>(), "chunked-limit-test")

    /** A deliberately tiny ceiling, so the test does not have to send megabytes. */
    private val api = Api(
        endpoints = listOf(echo handledNow { body -> "read ${body.text.length}" }),
        codecs = JacksonCodecs,
        maxBodyBytes = 1024,
    )

    private val binding: ServerBinding = Http.get(system)
        .newServerAt("127.0.0.1", 0)
        .bind(api.toRoute(system))
        .toCompletableFuture()
        .join()

    @AfterAll
    fun stop() {
        binding.unbind().toCompletableFuture().join()
        system.terminate()
        system.whenTerminated.toCompletableFuture().join()
    }

    private fun postChunked(body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${binding.localAddress().port}/notes"))
            .header("Content-Type", "application/json")
            // No length: the publisher is a stream, so the client chunks it.
            .POST(HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(body.toByteArray()) })
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `a chunked body over the limit is refused, though nothing declared its length`() {
        val res = postChunked("""{"text":"${"x".repeat(4_000)}"}""")

        res.statusCode() shouldBe 413
        withClue(res.body()) { res.body() shouldContain "Payload too large" }
        // Refused rather than decoded: none of the payload comes back.
        withClue(res.body().take(200)) { res.body() shouldNotContain "xxxx" }
    }

    @Test
    fun `a chunked body under the limit is read as normal`() {
        val res = postChunked("""{"text":"hello"}""")

        res.statusCode() shouldBe 200
        res.body() shouldBe "read 5"
    }
}
