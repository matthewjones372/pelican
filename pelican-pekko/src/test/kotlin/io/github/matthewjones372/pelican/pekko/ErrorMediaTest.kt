package io.github.matthewjones372.pelican.pekko

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.errorMedia
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.orFail
import io.kotest.matchers.shouldBe
import org.apache.pekko.http.javadsl.model.HttpRequest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.RegisterExtension

/** A failure declared in a media type of its own goes out with that Content-Type. See spec 0070. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErrorMediaTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoRouteTestKit("pelican-error-media-test")
    }

    data class Report(val status: String)

    data class Problem(val message: String)

    private val unavailable = errorMedia<Report>("application/health+json", 503, "A check failed")
    private val broken = errorJson<Problem>(500, "Something broke")

    private val ready = endpoint {
        get("ready")
        json<Report>().orFail(unavailable, broken)
    }

    private fun answering(failure: Boolean) = pekko.testRoute(
        api(
            endpoints = listOf(
                ready handledOrFail { _ -> if (failure) unavailable(Report("fail")) else broken(Problem("no")) },
            ),
            codecs = JacksonCodecs,
        ).toRoute(pekko.system()),
    ).run(HttpRequest.GET("/ready"))

    @Test
    fun `the failure goes out in its own media type`() {
        val res = answering(failure = true)

        res.assertStatusCode(503).assertEntity("""{"status":"fail"}""")
        res.response().entity().contentType.toString() shouldBe "application/health+json"
    }

    @Test
    fun `and a failure declared with errorJson is still application json`() {
        answering(failure = false).assertStatusCode(500).assertContentType("application/json")
    }
}
