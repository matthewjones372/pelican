package dev.surefold.pelican.gannet.served

import dev.surefold.pelican.ApiError
import dev.surefold.pelican.Outcome
import dev.surefold.pelican.Params
import dev.surefold.pelican.ServerEndpoint
import dev.surefold.pelican.api
import dev.surefold.pelican.div
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.errorJson
import dev.surefold.pelican.errorMedia
import dev.surefold.pelican.gannet.RequiresGannet
import dev.surefold.pelican.gannet.bytesOrFail
import dev.surefold.pelican.gannet.handledByOneOf
import dev.surefold.pelican.gannet.handledOrFail
import dev.surefold.pelican.gannet.start
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.json
import dev.surefold.pelican.of
import dev.surefold.pelican.ok
import dev.surefold.pelican.or
import dev.surefold.pelican.orFail
import dev.surefold.pelican.pathParam
import dev.surefold.pelican.responseHeader
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import dev.surefold.pelican.jetty.bytesOrFail as jettyBytesOrFail
import dev.surefold.pelican.jetty.handledByOneOf as jettyHandledByOneOf
import dev.surefold.pelican.jetty.handledOrFail as jettyHandledOrFail
import dev.surefold.pelican.jetty.start as jettyStart

/** Declared successes and failures on both servers, asked the same questions over a socket. */
@RequiresGannet
class FailuresAnsweredAsJettyAnswersThemTest {

    /** What a caller can see of an answer, the headers the declarations name included. */
    data class Seen(val status: Int, val contentType: String?, val headers: Map<String, String?>, val body: String)

    data class Report(val status: String)

    data class Problem(val message: String)

    data class Remembered(val who: String)

    data class NotRecorded(val attempt: Int)

    data class RegistryDown(val retryIn: Int)

    companion object {
        private val unavailable = errorMedia<Report>("application/health+json", 503, "A check failed")
        private val broken = errorJson<Problem>(500, "Something broke")

        private val check = pathParam<String>("check")
        private val ready = endpoint(check) {
            get("ready" / check)
            json<Report>().orFail(unavailable, broken)
        }

        private val blobId = pathParam<Long>("blobId")
        private val noSuchBlob = errorJson<ApiError>(404, "No blob with that id")
        private val fetchBlob = endpoint(blobId) {
            get("blobs" / blobId)
            bytes("application/octet-stream") orFail noSuchBlob
        }

        private val requestId = responseHeader<String>("X-Request-Id", "Correlates this answer")
        private val retryAfter = responseHeader<Long>("Retry-After", "Seconds to wait")
        private val tooMuch = errorJson<ApiError>(429, "Slow down", retryAfter)

        private val word = pathParam<String>("word")
        private val echo = endpoint(word) {
            get("echo" / word)
            emits(requestId)
            json<Report>() orFail tooMuch
        }

        private val location = responseHeader<String>("Location", "Where it lives")
        private val alreadyKnown = json<Remembered>(status = 200)
        private val newlyLearned = json<Remembered>(status = 201, location)

        private val who = pathParam<String>("who")
        private val remember = endpoint(who) {
            put("greetings" / who)
            emits(requestId)
            newlyLearned or alreadyKnown
        }

        private val notRecorded = errorJson<NotRecorded>(503, "Not recorded").tagged("not_recorded")
        private val registryDown = errorJson<RegistryDown>(503, "Registry down").tagged("registry_down")
        private val petId = pathParam<Long>("petId")
        private val adopt = endpoint(petId) {
            post("pets" / petId / "adoption")
            json<String>().orFail(notRecorded, registryDown)
        }

        private fun readiness(check: String): Outcome<Any, Report> =
            if (check == "chip") unavailable(Report("fail")) else broken(Problem("no"))

        private fun Params.echoed(word: String): Outcome<ApiError, Report> {
            setHeader(requestId, "r-$word")
            return if (word == "flood") tooMuch(ApiError(429, "Slow down"), retryAfter of 5L) else ok(Report(word))
        }

        private fun Params.remembered(who: String): CompletableFuture<Outcome<Nothing, Remembered>> {
            setHeader(requestId, "r-$who")
            val outcome =
                if (who == "ada") alreadyKnown(Remembered(who))
                else newlyLearned(Remembered(who), location of "/greetings/$who")
            return CompletableFuture.completedFuture(outcome)
        }

        private fun adoption(id: Long): Outcome<Any, String> = when (id) {
            1L -> notRecorded(NotRecorded(2))
            2L -> registryDown(RegistryDown(30))
            else -> ok("adopted $id")
        }

        private fun served(endpoints: List<ServerEndpoint>) = api(endpoints, JacksonCodecs)

        private val gannet = served(
            listOf(
                ready handledOrFail { c -> readiness(c) },
                fetchBlob bytesOrFail { id ->
                    if (id == 1L) ok(sequenceOf("blob".toByteArray(), "by".toByteArray()))
                    else noSuchBlob(ApiError(404, "No blob $id"))
                },
                echo handledOrFail { w -> echoed(w) },
                remember handledByOneOf { w -> remembered(w) },
                adopt handledOrFail { id -> adoption(id) },
            ),
        ).start(port = 0)

        private val jetty = served(
            listOf(
                ready jettyHandledOrFail { c -> readiness(c) },
                fetchBlob jettyBytesOrFail { id ->
                    if (id == 1L) ok(sequenceOf("blob".toByteArray(), "by".toByteArray()))
                    else noSuchBlob(ApiError(404, "No blob $id"))
                },
                echo jettyHandledOrFail { w -> echoed(w) },
                remember jettyHandledByOneOf { w -> remembered(w) },
                adopt jettyHandledOrFail { id -> adoption(id) },
            ),
        ).jettyStart(port = 0)

        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stopBoth() {
            client.close()
            gannet.stop()
            jetty.stop()
        }
    }

    private fun ask(baseUrl: String, ask: String): Seen {
        val (method, path) = ask.split(" ")
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        val res = client.send(request, HttpResponse.BodyHandlers.ofString())
        val named = listOf("Retry-After", "Location", "X-Request-Id")
            .associateWith { res.headers().firstValue(it).orElse(null) }
        return Seen(res.statusCode(), res.headers().firstValue("Content-Type").orElse(null), named, res.body())
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "GET /ready/chip", "GET /ready/disk",
            "GET /blobs/1", "GET /blobs/9",
            "GET /echo/flood", "GET /echo/hello",
            "PUT /greetings/ada", "PUT /greetings/zoe",
            "POST /pets/1/adoption", "POST /pets/2/adoption", "POST /pets/3/adoption",
        ],
    )
    fun `gannet answers a declared response as jetty does`(request: String) {
        withClue(request) { ask(gannet.baseUrl, request) shouldBe ask(jetty.baseUrl, request) }
    }

    @Test
    fun `the failure goes out in its own media type`() {
        ask(gannet.baseUrl, "GET /ready/chip") shouldBe
            Seen(503, "application/health+json", noHeaders, """{"status":"fail"}""")
    }

    @Test
    fun `and a failure declared with errorJson is still application json`() {
        val seen = ask(gannet.baseUrl, "GET /ready/disk")

        seen.status shouldBe 500
        seen.contentType shouldBe "application/json"
    }

    @Test
    fun `the stream is served when there is one`() {
        ask(gannet.baseUrl, "GET /blobs/1") shouldBe Seen(200, "application/octet-stream", noHeaders, "blobby")
    }

    @Test
    fun `and the declared failure is answered before any byte of it`() {
        val seen = ask(gannet.baseUrl, "GET /blobs/9")

        seen.status shouldBe 404
        seen.body shouldContain "No blob 9"
    }

    @Test
    fun `a declared failure carries its payload and its header`() {
        val seen = ask(gannet.baseUrl, "GET /echo/flood")

        seen.status shouldBe 429
        seen.contentType shouldBe "application/json"
        seen.headers shouldBe mapOf("Retry-After" to "5", "Location" to null, "X-Request-Id" to "r-flood")
        seen.body shouldContain "Slow down"
    }

    @Test
    fun `a header declared on a failure is absent from the success`() {
        ask(gannet.baseUrl, "GET /echo/hello").headers["Retry-After"] shouldBe null
    }

    @Test
    fun `each declared success gets its own status, and its own header`() {
        val known = ask(gannet.baseUrl, "PUT /greetings/ada")
        val learned = ask(gannet.baseUrl, "PUT /greetings/zoe")

        known.status shouldBe 200
        known.headers shouldBe mapOf("Retry-After" to null, "Location" to null, "X-Request-Id" to "r-ada")
        learned.status shouldBe 201
        learned.headers shouldBe mapOf("Retry-After" to null, "Location" to "/greetings/zoe", "X-Request-Id" to "r-zoe")
    }

    @Test
    fun `a tagged failure says which one it is, first`() {
        ask(gannet.baseUrl, "POST /pets/2/adoption").body shouldBe """{"kind":"registry_down","retryIn":30}"""
    }

    private val noHeaders = mapOf("Retry-After" to null, "Location" to null, "X-Request-Id" to null)
}
