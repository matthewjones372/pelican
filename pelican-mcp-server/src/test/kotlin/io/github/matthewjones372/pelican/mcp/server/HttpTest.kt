package io.github.matthewjones372.pelican.mcp.server

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.parseJson
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * The request/response half of Streamable HTTP, decided here rather than three
 * times over: what a backend module mounts is a status, a content type and a
 * body.
 */
class HttpTest {

    data class Order(val id: Long, val item: String)

    private val orders = endpoint {
        get("orders")
        operationId = "orders"
        json<Order>()
    }

    private fun service(): Api = api(
        endpoints = listOf(ServerEndpoint(orders) { _ -> CompletableFuture.completedStage(Order(1, "a-widget")) }),
        codecs = JacksonCodecs,
    ) { title = "Orders" }

    private fun post(message: String): McpHttpResponse =
        service().mcpServer().post(message).toCompletableFuture().join()

    @Test
    fun `a request is answered with the message, as JSON`() {
        val answer = post("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")

        answer.status shouldBe 200
        answer.contentType shouldBe "application/json"
        ((parseJson(answer.body) as JsonObj)["result"] as JsonObj)["tools"].toString() shouldContain "orders"
    }

    @Test
    fun `a notification is accepted, and there is nothing to send back`() {
        val answer = post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        withClue("a 200 with an empty body is a message the client would try to parse") {
            answer.status shouldBe 202
        }
        answer.contentType.shouldBeNull()
        answer.body shouldBe ""
    }

    @Test
    fun `anything but a POST is told what this endpoint answers`() {
        val answer = mcpMethodNotAllowed()

        answer.status shouldBe 405
        val error = (parseJson(answer.body) as JsonObj)["error"] as JsonObj
        withClue("a 404 would say the tools are not here, which is the one thing that is not true") {
            (error["message"] as JsonStr).value shouldContain "POST"
        }
    }

    @Test
    fun `a message that is not JSON is still an answer rather than a dropped connection`() {
        val answer = post("{not json")

        answer.status shouldBe 200
        ((parseJson(answer.body) as JsonObj)["error"] as JsonObj)["code"].toString() shouldContain "-32700"
    }
}
