package io.github.matthewjones372.pelican.gannet.docs

import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.gannet.RequiresGannet
import io.github.matthewjones372.pelican.gannet.handledNow
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
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

/** `responseHeaders { }` on the document and the reference page, told the docs modules served them. */
@RequiresGannet
class DocsResponseHeadersTest {

    companion object {
        private val widgetId = pathParam<Long>("widgetId")

        private val getWidget = endpoint(widgetId) {
            get("widgets" / widgetId)
            operationId = "getWidget"
            json<Widget>()
        }

        private val gannet by lazy {
            api(listOf(getWidget handledNow { id -> Widget(id, "widget-$id") }), JacksonCodecs) {
                responseHeaders { answer -> add("X-Docs", answer.isDocs.toString()) }
            }.startWithDocs(port = 0, docs = docs { reference("/reference") })
        }

        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stopAll() {
            client.close()
            gannet.stop()
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource("/openapi.json, 200, true", "/reference, 200, true", "/widgets/1, 200, false", "/nope, 404, false")
    fun `the policy is told whether the docs modules served the answer`(path: String, status: Int, isDocs: String) {
        val res = client.send(
            HttpRequest.newBuilder(URI.create(gannet.baseUrl + path)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        withClue(path) {
            (res.statusCode() to res.headers().firstValue("X-Docs").orElse(null)) shouldBe
                (status to isDocs)
        }
    }
}
