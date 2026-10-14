package io.github.matthewjones372.pelican.mcp.server

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.mcp.mcpOptions
import io.github.matthewjones372.pelican.parseJson
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CompletableFuture

/**
 * A whole session over two streams. The framing is the transport's only job —
 * one JSON message per line — so the streams are in memory and no process is
 * started.
 */
class StdioTest {

    data class Order(val id: Long, val item: String)

    private val userId = pathParam<Long>("userId")

    private val getOrder = endpoint(userId) {
        get("users" / userId / "order")
        operationId = "getOrder"
        json<Order>()
    }

    private val hidden = endpoint {
        get("admin" / "reindex")
        operationId = "reindex"
        hidden = true
        json<Order>()
    }

    private fun service(): Api = api(
        endpoints = listOf(
            ServerEndpoint(getOrder) { p -> CompletableFuture.completedStage(Order(p[userId], "a-widget")) },
            ServerEndpoint(hidden) { _ -> CompletableFuture.completedStage(Order(0, "never")) },
        ),
        codecs = JacksonCodecs,
    ) { title = "Orders" }

    private fun session(vararg lines: String): List<JsonObj> {
        val output = ByteArrayOutputStream()
        mcpServe(
            service(),
            input = lines.joinToString("\n").byteInputStream(),
            output = output,
        )
        return output.toString(Charsets.UTF_8)
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { parseJson(it) as JsonObj }
            .toList()
    }

    @Test
    fun `initialize, initialized, list and call, one message per line`() {
        val answers = session(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$MCP_PROTOCOL_VERSION"}}""",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"getOrder","arguments":{"userId":7}}}""",
        )

        withClue("the notification takes no answer, so three lines come back rather than four") {
            answers shouldHaveSize 3
        }
        answers.map { it["id"] } shouldBe listOf(1, 2, 3).map { JsonNum(it) }

        val tools = ((answers[1]["result"] as JsonObj)["tools"] as JsonArr).items.map { (it as JsonObj)["name"] }
        withClue("`hidden` is not written down here either") {
            tools shouldBe listOf(JsonStr("getOrder"))
        }

        val content = ((answers[2]["result"] as JsonObj)["content"] as JsonArr).items.single() as JsonObj
        content["text"] shouldBe JsonStr("""{"id":7,"item":"a-widget"}""")
    }

    @Test
    fun `a blank line is skipped and a bad message does not end the session`() {
        val answers = session(
            "",
            "{not json",
            """{"jsonrpc":"2.0","id":2,"method":"ping"}""",
        )

        answers shouldHaveSize 2
        (answers[0]["error"] as JsonObj)["code"] shouldBe JsonNum(PARSE_ERROR)
        withClue("a session that stops at the first bad line is a session a client cannot recover") {
            answers[1]["result"] shouldBe JsonObj(emptyMap())
        }
    }

    @Test
    fun `the tools a session publishes are the ones the options let through`() {
        val output = ByteArrayOutputStream()
        mcpServe(
            service(),
            options = mcpOptions { include = { false } },
            input = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""".byteInputStream(),
            output = output,
        )

        val result = (parseJson(output.toString(Charsets.UTF_8).trim()) as JsonObj)["result"] as JsonObj
        (result["tools"] as JsonArr).items shouldHaveSize 0
    }
}
