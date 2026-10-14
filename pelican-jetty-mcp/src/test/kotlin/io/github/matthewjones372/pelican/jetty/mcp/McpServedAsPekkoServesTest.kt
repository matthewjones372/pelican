package io.github.matthewjones372.pelican.jetty.mcp

import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jetty.handledNow
import io.github.matthewjones372.pelican.jetty.start
import io.github.matthewjones372.pelican.mcp.server.MCP_PROTOCOL_VERSION
import io.github.matthewjones372.pelican.parseJson
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import io.github.matthewjones372.pelican.pekko.handledNow as pekkoHandledNow
import io.github.matthewjones372.pelican.pekko.mcp.routeWithMcp as pekkoRouteWithMcp
import io.github.matthewjones372.pelican.pekko.start as pekkoStart

data class Widget(val id: Long, val name: String)

/** One API and its tools on both servers, asked the same questions over a socket. */
class McpServedAsPekkoServesTest {

    data class Seen(val status: Int, val contentType: String?, val body: String)

    companion object {
        private val widgetId = pathParam<Long>("widgetId")

        private val getWidget = endpoint(widgetId) {
            get("widgets" / widgetId)
            operationId = "getWidget"
            json<Widget>()
        }

        private fun served(endpoint: ServerEndpoint) = api(listOf(endpoint), JacksonCodecs) { title = "Widgets" }

        private val jetty = served(getWidget handledNow { id -> Widget(id, "widget-$id") })
            .start(port = 0) { handlerWithMcp() }

        private val pekko = served(getWidget pekkoHandledNow { id -> Widget(id, "widget-$id") })
            .pekkoStart(port = 0) { system -> pekkoRouteWithMcp(system) }

        private val moved = served(getWidget handledNow { id -> Widget(id, "widget-$id") })
            .start(port = 0) { mcpHandler(path = "/internal/mcp") }

        private val client: HttpClient = HttpClient.newHttpClient()

        @JvmStatic
        @AfterAll
        fun stopAll() {
            client.close()
            jetty.stop()
            pekko.stop()
            moved.stop()
        }

        private const val TOOLS_LIST = """{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""
        private const val INITIALIZED = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        private const val CALL =
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getWidget","arguments":{"widgetId":3}}}"""
    }

    private fun ask(baseUrl: String, method: String, path: String, body: String? = null): Seen {
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path)).method(method, publisher).build()
        val res = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Seen(res.statusCode(), res.headers().firstValue("Content-Type").orElse(null), res.body())
    }

    private fun Seen.result(): JsonObj = (parseJson(body) as JsonObj)["result"] as JsonObj

    @Test
    fun `tools list over streamable http answers as on pekko`() {
        val seen = ask(jetty.baseUrl, "POST", "/mcp", TOOLS_LIST)

        seen shouldBe ask(pekko.baseUrl, "POST", "/mcp", TOOLS_LIST)
        seen.status shouldBe 200
        ((seen.result()["tools"] as JsonArr).items.single() as JsonObj)["name"] shouldBe JsonStr("getWidget")
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        "GET, /mcp",
        "GET, /widgets/3",
        "GET, /nobody/described/this",
    )
    fun `jetty answers as pekko does`(method: String, path: String) {
        withClue("$method $path") {
            ask(jetty.baseUrl, method, path) shouldBe ask(pekko.baseUrl, method, path)
        }
    }

    @Test
    fun `initialize, a call and a notification answer as on pekko`() {
        val initialize =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION"}}"""
        for (message in listOf(initialize, CALL, INITIALIZED)) {
            withClue(message) {
                ask(jetty.baseUrl, "POST", "/mcp", message) shouldBe ask(pekko.baseUrl, "POST", "/mcp", message)
            }
        }
        val content = (ask(jetty.baseUrl, "POST", "/mcp", CALL).result()["content"] as JsonArr).items.single()
        (content as JsonObj)["text"] shouldBe JsonStr("""{"id":3,"name":"widget-3"}""")
    }

    @Test
    fun `a get is told what the endpoint answers rather than left to a 404`() {
        val seen = ask(jetty.baseUrl, "GET", "/mcp")

        seen.status shouldBe 405
        (((parseJson(seen.body) as JsonObj)["error"] as JsonObj)["message"] as JsonStr).value shouldContain "POST"
    }

    @Test
    fun `the path is configuration, and the handler alone declines what is not its own`() {
        ask(moved.baseUrl, "POST", "/internal/mcp", TOOLS_LIST).status shouldBe 200
        ask(moved.baseUrl, "POST", "/mcp", TOOLS_LIST).status shouldBe 404
    }
}
