package io.github.matthewjones372.pelican.ktor.mcp

import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ktor.handledNow
import io.github.matthewjones372.pelican.ktor.pelican
import io.github.matthewjones372.pelican.mcp.server.MCP_PROTOCOL_VERSION
import io.github.matthewjones372.pelican.parseJson
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test

data class Widget(val id: Long, val name: String)

private val widgetId = pathParam<Long>("widgetId")

private val getWidget = endpoint(widgetId) {
    get("widgets" / widgetId)
    operationId = "getWidget"
    json<Widget>()
}

private fun api() = api(
    endpoints = listOf(getWidget handledNow { id -> Widget(id, "widget-$id") }),
    codecs = JacksonCodecs,
) {
    title = "Widgets"
}

private suspend fun HttpResponse.result(): JsonObj {
    val answer = parseJson(bodyAsText()) as JsonObj
    withClue("expected a result, and the answer was $answer") { answer["result"].shouldNotBeNull() }
    return answer["result"] as JsonObj
}

/**
 * A session over HTTP: initialize, list, call — against the same handler the
 * endpoint route calls.
 */
class McpTest {

    @Test
    fun `initialize, tools list and tools call, beside the endpoints`() = testApplication {
        application {
            routing {
                pelicanMcp(api())
                pelican(api())
            }
        }

        val handshake = client.post("/mcp") {
            setBody(
                """{"jsonrpc":"2.0","id":1,"method":"initialize",""" +
                    """"params":{"protocolVersion":"$MCP_PROTOCOL_VERSION"}}""",
            )
        }
        handshake.status.value shouldBe 200
        handshake.headers[HttpHeaders.ContentType] shouldContain "application/json"
        handshake.result()["protocolVersion"] shouldBe JsonStr(MCP_PROTOCOL_VERSION)

        val tools = client.post("/mcp") { setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""") }.result()
        ((tools["tools"] as JsonArr).items.single() as JsonObj)["name"] shouldBe JsonStr("getWidget")

        val called = client.post("/mcp") {
            setBody(
                """{"jsonrpc":"2.0","id":3,"method":"tools/call",""" +
                    """"params":{"name":"getWidget","arguments":{"widgetId":3}}}""",
            )
        }.result()
        val content = (called["content"] as JsonArr).items.single() as JsonObj
        content["text"] shouldBe JsonStr("""{"id":3,"name":"widget-3"}""")

        withClue("the endpoints are still there; the tools are an addition, not a wrapper") {
            client.get("/widgets/3").bodyAsText() shouldBe """{"id":3,"name":"widget-3"}"""
        }
    }

    @Test
    fun `a GET is told what the endpoint answers rather than left to a 404`() = testApplication {
        application { routing { pelicanMcp(api()) } }

        val answer = client.get("/mcp")
        answer.status.value shouldBe 405
        val error = (parseJson(answer.bodyAsText()) as JsonObj)["error"] as JsonObj
        (error["message"] as JsonStr).value shouldContain "POST"
    }

    @Test
    fun `a notification is accepted and answered with nothing`() = testApplication {
        application { routing { pelicanMcp(api()) } }

        val answer = client.post("/mcp") { setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""") }
        answer.status.value shouldBe 202
        answer.bodyAsText() shouldBe ""
    }
}
