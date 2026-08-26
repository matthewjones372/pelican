package example.backends

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * A request body that declares no length is still refused when it is too big.
 *
 * A `Content-Length` is refused before a byte is transferred, and that is the
 * branch every other size test here takes. A chunked upload declares nothing,
 * so the only thing between the service and an unbounded read is the counting
 * `readStrictBody` does — a second branch, written once per backend, and
 * asserted until now on Pekko alone (`ChunkedBodyLimitTest`, which also pins
 * what that backend does with the bytes still arriving after the refusal).
 * This is the same claim asked of the other two.
 *
 * `BodyPublishers.ofInputStream` is how the JDK client is made to chunk: it
 * cannot know the length in advance either.
 */
class ChunkedUploadTest {

    companion object {
        /** Pekko's is `ChunkedBodyLimitTest`, over a raw socket, in its own module. */
        private val backends = allBackends.filterNot { it.name == "pekko" }

        private val running: Map<String, Running> = backends.associate { it.name to it.start(port = 0) }

        @JvmStatic
        fun chunking(): List<Array<Any>> = backends.map { arrayOf(it.name, running.getValue(it.name).baseUrl) }

        @JvmStatic
        @AfterAll
        fun stopAll() {
            running.values.forEach { it.stop() }
        }

        /** `greetingsApi` reads 4,096 bytes; this is comfortably past it. */
        private const val OVERSIZE = 8_000
    }

    private fun postChunked(baseUrl: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/echo"))
            .header("Content-Type", "application/json")
            // No length: the publisher is a stream, so the client chunks it.
            .POST(HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(body.toByteArray()) })
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chunking")
    fun `a chunked body over the limit is refused, though nothing declared its length`(
        name: String,
        baseUrl: String,
    ) {
        val res = postChunked(baseUrl, """{"text":"${"x".repeat(OVERSIZE)}"}""")

        withClue(name) {
            res.statusCode() shouldBe 413
            withClue(res.body()) { res.body() shouldContain "Payload too large" }
            // Refused rather than decoded: none of the payload comes back.
            withClue(res.body().take(200)) { res.body() shouldNotContain "xxxx" }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("chunking")
    fun `a chunked body under the limit is read as normal`(name: String, baseUrl: String) {
        val res = postChunked(baseUrl, """{"text":"hello"}""")

        withClue(name) {
            res.statusCode() shouldBe 200
            res.body() shouldContain """"text":"hello""""
        }
    }
}
