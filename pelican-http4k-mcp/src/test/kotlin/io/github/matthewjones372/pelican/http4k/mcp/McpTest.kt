package io.github.matthewjones372.pelican.http4k.mcp

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.http4k.handledNow
import io.github.matthewjones372.pelican.http4k.toHttpHandler
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.mcp.server.MCP_PROTOCOL_VERSION
import io.github.matthewjones372.pelican.parseJson
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.routing.routes
import org.junit.jupiter.api.Test

data class Widget(val id: Long, val name: String)

private val widgetId = pathParam<Long>("widgetId")

private val getWidget = endpoint(widgetId) {
    get("widgets" / widgetId)
    operationId = "getWidget"
    json<Widget>()
}

private fun service(): Api = api(
    endpoints = listOf(getWidget handledNow { id -> Widget(id, "widget-$id") }),
    codecs = JacksonCodecs,
) { title = "Widgets" }

/**
 * A session over HTTP: initialize, list, call — against the same handler the
 * endpoint route calls.
 */
class McpTest {

    private val handler = routes(service().mcpRoutes() + service().toHttpHandler())

    private fun post(message: String): Response =
        handler(Request(Method.POST, "/mcp").body(message))

    private fun Response.result(): JsonObj = (parseJson(bodyString()) as JsonObj)["result"] as JsonObj

    @Test
    fun `initialize, tools list and tools call, over the endpoint the service already serves`() {
        val handshake = post(
            """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
                """"params":{"protocolVersion":"$MCP_PROTOCOL_VERSION"}}""",
        )
        handshake.status.code shouldBe 200
        handshake.header("Content-Type") shouldBe "application/json"
        handshake.result()["protocolVersion"] shouldBe JsonStr(MCP_PROTOCOL_VERSION)

        val tools = post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""").result()
        ((tools["tools"] as JsonArr).items.single() as JsonObj)["name"] shouldBe JsonStr("getWidget")

        val called = post(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call",""" +
                """"params":{"name":"getWidget","arguments":{"widgetId":3}}}""",
        ).result()
        val content = (called["content"] as JsonArr).items.single() as JsonObj
        content["text"] shouldBe JsonStr("""{"id":3,"name":"widget-3"}""")

        withClue("the endpoints are still there; the tools are an addition, not a wrapper") {
            handler(Request(Method.GET, "/widgets/3")).bodyString() shouldBe """{"id":3,"name":"widget-3"}"""
        }
    }

    @Test
    fun `a GET is told what the endpoint answers rather than left to a 404`() {
        val answer = handler(Request(Method.GET, "/mcp"))

        answer.status.code shouldBe 405
        val error = (parseJson(answer.bodyString()) as JsonObj)["error"] as JsonObj
        (error["message"] as JsonStr).value shouldContain "POST"
    }

    @Test
    fun `a notification is accepted and answered with nothing`() {
        val answer = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        answer.status.code shouldBe 202
        answer.bodyString() shouldBe ""
    }

    @Test
    fun `the path is configuration, so a service that already routes mcp can move it`() {
        val elsewhere = routes(service().mcpRoutes(path = "/internal/mcp"))

        elsewhere(Request(Method.POST, "/internal/mcp").body("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
            .status.code shouldBe 200
        elsewhere(Request(Method.POST, "/mcp").body("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
            .status.code shouldBe 404
    }
}
