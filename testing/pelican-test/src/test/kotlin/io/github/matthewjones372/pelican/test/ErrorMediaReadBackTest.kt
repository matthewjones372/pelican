package io.github.matthewjones372.pelican.test

import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorMedia
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * A failure declared in a media type of its own, served and read back: a health probe's 503 in
 * `application/health+json` is a failure on the wire, so it comes back as one rather than as a
 * call that failed. See spec 0070.
 */
class ErrorMediaReadBackTest {

    data class Report(val status: String)

    private val unavailable = errorMedia<Report>("application/health+json", 503, "A check failed")

    private val ready = endpoint {
        get("ready")
        json<Report>() orFail unavailable
    }

    private fun serving(passing: Boolean) = api(
        endpoints = listOf(
            ServerEndpoint(ready) { _ ->
                CompletableFuture.completedStage(if (passing) ok(Report("pass")) else unavailable(Report("fail")))
            },
        ),
        codecs = JacksonCodecs,
    ).inMemory()

    @Test
    fun `the failure is sent in its own media type`() {
        val response = serving(passing = false).response(ready, Unit)

        response.status shouldBe 503
        response.header("Content-Type") shouldBe "application/health+json"
        response.body shouldBe """{"status":"fail"}"""
    }

    @Test
    fun `and read back as the declared failure, with its body decoded`() {
        serving(passing = false).outcome(ready, Unit) shouldBe Outcome.Err(unavailable, Report("fail"), emptyList())
    }

    @Test
    fun `while a pass is the success it always was`() {
        serving(passing = true).outcome(ready, Unit).shouldBeOk() shouldBe Report("pass")
    }
}
