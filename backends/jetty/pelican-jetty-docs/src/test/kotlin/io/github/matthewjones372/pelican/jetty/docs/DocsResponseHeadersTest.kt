package io.github.matthewjones372.pelican.jetty.docs

import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.openapi.docs
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.github.matthewjones372.pelican.pekko.docs.startWithDocs as pekkoStartWithDocs
import io.github.matthewjones372.pelican.pekko.handledNow as pekkoHandledNow

/** `responseHeaders { }` on the document and the reference page, told the docs modules served them, on both servers. */
class DocsResponseHeadersTest {

    companion object {
        private val widgetId = pathParam<Long>("widgetId")

        private val getWidget = endpoint(widgetId) {
            get("widgets" / widgetId)
            operationId = "getWidget"
            json<Widget>()
        }

        private fun served(endpoint: ServerEndpoint) = api(listOf(endpoint), JacksonCodecs) {
            responseHeaders { answer -> add("X-Docs", answer.isDocs.toString()) }
        }

        private val withReference = docs { reference("/reference") }

        private val jetty = served(getWidget handledNow { id -> Widget(id, "widget-$id") })
            .startWithDocs(port = 0, docs = withReference)
        private val pekko = served(getWidget pekkoHandledNow { id -> Widget(id, "widget-$id") })
            .pekkoStartWithDocs(port = 0, docs = withReference)

        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stopAll() {
            client.close()
            jetty.stop()
            pekko.stop()
        }
    }

    private fun docsHeader(baseUrl: String, path: String): Pair<Int, String?> {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path)).build()
        val res = client.send(request, HttpResponse.BodyHandlers.ofString())
        return res.statusCode() to res.headers().firstValue("X-Docs").orElse(null)
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "jetty, /openapi.json, 200, true",
        "jetty, /reference, 200, true",
        "jetty, /widgets/1, 200, false",
        "jetty, /nope, 404, false",
        "pekko, /openapi.json, 200, true",
        "pekko, /reference, 200, true",
        "pekko, /widgets/1, 200, false",
        "pekko, /nope, 404, false",
    )
    fun `the policy is told whether the docs modules served the answer`(
        server: String,
        path: String,
        status: Int,
        isDocs: String,
    ) {
        val baseUrl = if (server == "jetty") jetty.baseUrl else pekko.baseUrl
        withClue("$server $path") { docsHeader(baseUrl, path) shouldBe (status to isDocs) }
    }
}
