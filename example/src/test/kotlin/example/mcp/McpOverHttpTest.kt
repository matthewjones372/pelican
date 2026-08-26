package example.mcp

import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.mcp.server.MCP_PROTOCOL_VERSION
import io.github.matthewjones372.pelican.parseJson
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The service this repository runs, called by a model's client over HTTP:
 * initialize, tools/list, tools/call — against the handlers the routes have,
 * on the port the endpoints are served from.
 */
class McpOverHttpTest {

    companion object {
        @JvmStatic
        private val server = ordersWithTools(port = 0)

        @JvmStatic
        @AfterAll
        fun stop() {
            server.stop()
        }
    }

    private val http: HttpClient = HttpClient.newHttpClient()

    private fun post(message: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("${server.baseUrl}/mcp"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(message))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun HttpResponse<String>.result(): JsonObj {
        statusCode() shouldBe 200
        val answer = parseJson(body()) as JsonObj
        withClue("expected a result, and the answer was $answer") { answer["error"] shouldBe null }
        return answer["result"] as JsonObj
    }

    @Test
    fun `a whole session — the handshake, the tools, and one of them called`() {
        val handshake = post(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{""" +
                """"protocolVersion":"$MCP_PROTOCOL_VERSION","capabilities":{},""" +
                """"clientInfo":{"name":"a-test","version":"1"}}}""",
        ).result()
        handshake["protocolVersion"] shouldBe JsonStr(MCP_PROTOCOL_VERSION)
        (handshake["serverInfo"] as JsonObj)["name"] shouldBe JsonStr("Orders")

        // A notification, which is answered by acceptance and nothing else.
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""").statusCode() shouldBe 202

        val listed = post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").result()
        val names = (listed["tools"] as JsonArr).items.map { ((it as JsonObj)["name"] as JsonStr).value }
        names shouldContainExactly listOf(
            "getUser", "placeOrder", "submitOrder", "placeOrderForm", "payOrder", "cancelOrder",
        )
        withClue("an endpoint that streams its answer is not a tool, and is left out by name") {
            names shouldNotContain "streamOrders"
        }

        val called = post(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call",""" +
                """"params":{"name":"getUser","arguments":{"userId":1}}}""",
        ).result()
        val content = (called["content"] as JsonArr).items.single() as JsonObj
        (content["text"] as JsonStr).value shouldContain "\"id\":1"
        withClue("getUser answers one JSON shape, so the tool published a schema and the data comes back too") {
            called["structuredContent"] shouldBe parseJson((content["text"] as JsonStr).value)
        }
    }

    @Test
    fun `a declared failure is something the model can act on`() {
        val called = post(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call",""" +
                """"params":{"name":"getUser","arguments":{"userId":999}}}""",
        ).result()

        called["isError"].toString() shouldContain "true"
        val text = ((called["content"] as JsonArr).items.single() as JsonObj)["text"] as JsonStr
        text.value shouldContain "404 No user with that id"
    }

    @Test
    fun `the credential the endpoints require is supplied by what serves the tools`() {
        val called = post(
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"placeOrder",""" +
                """"arguments":{"userId":1,"body":{"item":"a-widget","quantity":2}}}}""",
        ).result()

        withClue("no X-Api-Key was in the arguments, and the endpoint requires one") {
            called["isError"].toString() shouldContain "false"
        }
    }

    @Test
    fun `a GET is answered with what the endpoint does take`() {
        val answer = http.send(
            HttpRequest.newBuilder(URI.create("${server.baseUrl}/mcp")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        answer.statusCode() shouldBe 405
        answer.body() shouldContain "POST"
    }
}
