package dev.surefold.pelican.jetty.mcp

import dev.surefold.pelican.ServerEndpoint
import dev.surefold.pelican.api
import dev.surefold.pelican.div
import dev.surefold.pelican.endpoint
import dev.surefold.pelican.jackson.JacksonCodecs
import dev.surefold.pelican.jetty.handledNow
import dev.surefold.pelican.jetty.start
import dev.surefold.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import dev.surefold.pelican.pekko.handledNow as pekkoHandledNow
import dev.surefold.pelican.pekko.mcp.routeWithMcp as pekkoRouteWithMcp
import dev.surefold.pelican.pekko.start as pekkoStart

/** `responseHeaders { }` on what `/mcp` answers, a JSON-RPC reply and a refused GET alike, on both servers. */
class McpResponseHeadersTest {

    companion object {
        private val widgetId = pathParam<Long>("widgetId")

        private val getWidget = endpoint(widgetId) {
            get("widgets" / widgetId)
            operationId = "getWidget"
            json<Widget>()
        }

        private fun served(endpoint: ServerEndpoint) = api(listOf(endpoint), JacksonCodecs) {
            responseHeaders { answer -> add("X-Answered", "${answer.status} ${answer.isDocs}") }
        }

        private val jetty = served(getWidget handledNow { id -> Widget(id, "widget-$id") })
            .start(port = 0) { handlerWithMcp() }

        private val pekko = served(getWidget pekkoHandledNow { id -> Widget(id, "widget-$id") })
            .pekkoStart(port = 0) { system -> pekkoRouteWithMcp(system) }

        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stopAll() {
            client.close()
            jetty.stop()
            pekko.stop()
        }

        private const val TOOLS_LIST = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource("jetty, POST, 200", "jetty, GET, 405", "pekko, POST, 200", "pekko, GET, 405")
    fun `the MCP handler's answers carry the policy`(server: String, method: String, status: Int) {
        val baseUrl = if (server == "jetty") jetty.baseUrl else pekko.baseUrl
        val body = if (method == "POST") HttpRequest.BodyPublishers.ofString(TOOLS_LIST)
        else HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/mcp")).method(method, body).build()
        val res = client.send(request, HttpResponse.BodyHandlers.ofString())

        withClue("$server $method ${res.body()}") {
            res.statusCode() shouldBe status
            res.headers().firstValue("X-Answered").orElse(null) shouldBe "$status false"
        }
    }
}
