package io.github.matthewjones372.pelican.mcp.server

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.ApiErrorEnvelope
import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonBool
import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.JsonValue
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.ProblemDetails
import io.github.matthewjones372.pelican.RefusalRenderer
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.parseJson
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * The protocol as a client speaks it: one JSON-RPC message in, one out. What a
 * transport carries is a string, so nothing here needs a stream or a port.
 */
class ProtocolTest {

    data class Order(val id: Long, val item: String)
    data class Refusal(val status: Int, val error: String)

    private val userId = pathParam<Long>("userId")
    private val noSuchUser = errorJson<Refusal>(404, "No user with that id")

    private val getOrder = endpoint(userId) {
        get("users" / userId / "order")
        summary = "The user's order"
        operationId = "getOrder"
        json<Order>() orFail noSuchUser
    }

    private val reindex = endpoint {
        post("admin" / "reindex")
        operationId = "reindex"
        json<Order>()
    }

    /** References the hook was handed, so a test can say the log and the answer name the same failure. */
    private val logged = ArrayDeque<String>()

    private fun service(refusals: RefusalRenderer = ApiErrorEnvelope): Api = api(
        endpoints = listOf(
            ServerEndpoint(getOrder) { p ->
                val outcome =
                    if (p[userId] == 0L) Outcome.Err(noSuchUser, Refusal(404, "No user 0"))
                    else ok(Order(1, "a-widget"))
                CompletableFuture.completedStage(outcome)
            },
            // Throws where the handler is called rather than inside the stage
            // it would have returned, which is the harder of the two paths.
            ServerEndpoint(reindex) { _ -> error("the index is on fire") },
        ),
        codecs = JacksonCodecs,
    ) {
        title = "Orders"
        version = "2.0.0"
        refusals(refusals)
        onError { reference, _, _ -> logged += reference }
    }

    private fun McpServer.answering(message: String): JsonObj {
        val reply: String? = handle(message).toCompletableFuture().join()
        withClue("`$message` went unanswered") { reply.shouldNotBeNull() }
        return parseJson(reply!!) as JsonObj
    }

    private fun request(id: Int, method: String, params: String = "{}"): String =
        """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

    private fun JsonObj.result(): JsonObj {
        withClue("expected a result but the answer was $this") { this["error"].shouldBeNull() }
        this["jsonrpc"] shouldBe JsonStr("2.0")
        return this["result"] as JsonObj
    }

    private fun JsonObj.failure(): JsonObj {
        withClue("expected an error but the answer was $this") { this["result"].shouldBeNull() }
        return this["error"] as JsonObj
    }

    @Test
    fun `the handshake answers with the revision this speaks and the service behind it`() {
        val result = service().mcpServer().answering(
            request(
                1,
                "initialize",
                """{"protocolVersion":"$MCP_PROTOCOL_VERSION","capabilities":{},""" +
                    """"clientInfo":{"name":"probe","version":"1"}}""",
            ),
        ).result()

        result["protocolVersion"] shouldBe JsonStr(MCP_PROTOCOL_VERSION)
        (result["serverInfo"] as JsonObj)["name"] shouldBe JsonStr("Orders")
        (result["serverInfo"] as JsonObj)["version"] shouldBe JsonStr("2.0.0")
        withClue("a server that publishes no tools capability is one a client will not call tools on") {
            (result["capabilities"] as JsonObj)["tools"].shouldNotBeNull()
        }
    }

    @Test
    fun `a client asking for a revision this does not speak is told which one it does`() {
        val result = service().mcpServer()
            .answering(request(1, "initialize", """{"protocolVersion":"2024-11-05"}"""))
            .result()

        result["protocolVersion"] shouldBe JsonStr(MCP_PROTOCOL_VERSION)
    }

    @Test
    fun `the tool list is the one the descriptions derive`() {
        val result = service().mcpServer().answering(request(2, "tools/list")).result()
        val tools = (result["tools"] as JsonArr).items.map { (it as JsonObj)["name"] }

        tools shouldContainExactly listOf(JsonStr("getOrder"), JsonStr("reindex"))
        val getOrder = (result["tools"] as JsonArr).items.first() as JsonObj
        withClue("a tool with no input schema is one a model cannot fill in") {
            (getOrder["inputSchema"] as JsonObj)["properties"].shouldNotBeNull()
        }
    }

    @Test
    fun `a tool call runs the handler the route has, and answers as text and as data`() {
        val result = service().mcpServer()
            .answering(request(3, "tools/call", """{"name":"getOrder","arguments":{"userId":7}}"""))
            .result()

        val content = (result["content"] as JsonArr).items.single() as JsonObj
        content["type"] shouldBe JsonStr("text")
        content["text"] shouldBe JsonStr("""{"id":1,"item":"a-widget"}""")
        (result["structuredContent"] as JsonObj)["item"] shouldBe JsonStr("a-widget")
        result["isError"] shouldBe JsonBool(false)
    }

    @Test
    fun `a declared failure is a result carrying isError, not a protocol error`() {
        val result = service().mcpServer()
            .answering(request(4, "tools/call", """{"name":"getOrder","arguments":{"userId":0}}"""))
            .result()

        result["isError"] shouldBe JsonBool(true)
        val text = ((result["content"] as JsonArr).items.single() as JsonObj)["text"] as JsonStr
        withClue("a model reads the sentence the endpoint wrote for this, and tries again") {
            text.value shouldContain "404 No user with that id"
        }
    }

    @Test
    fun `what nobody declared is a protocol error carrying the reference the hook was given`() {
        val error = service().mcpServer()
            .answering(request(5, "tools/call", """{"name":"reindex","arguments":{}}"""))
            .failure()

        error["code"] shouldBe JsonNum(INTERNAL_ERROR)
        val reference = logged.single()
        withClue("the answer and the log line have to name the same failure") {
            (error["message"] as JsonStr).value shouldContain reference
        }
        withClue("the message a handler threw may name a table, a host or a query") {
            (error["message"] as JsonStr).value shouldNotContain "on fire"
        }
    }

    /**
     * `refusals(...)` chooses the envelope an HTTP caller reads. MCP answers in
     * JSON-RPC, whose shape the protocol fixes and a service does not pick, so a
     * problem+json document has no place inside a tool result's `text`.
     */
    @Test
    fun `the envelope an HTTP caller reads does not reach a tool result`() {
        fun answers(renderer: RefusalRenderer): Pair<JsonValue?, JsonValue?> {
            val server = service(renderer).mcpServer()
            val declared = server
                .answering(request(8, "tools/call", """{"name":"getOrder","arguments":{"userId":0}}"""))
                .result()
            val undeclared = server
                .answering(request(9, "tools/call", """{"name":"reindex","arguments":{}}"""))
                .failure()
            return ((declared["content"] as JsonArr).items.single() as JsonObj)["text"] to undeclared["code"]
        }

        val (problemText, problemCode) = answers(ProblemDetails)
        val (envelopeText, envelopeCode) = answers(ApiErrorEnvelope)

        problemText shouldBe envelopeText
        problemCode shouldBe envelopeCode
    }

    @Test
    fun `a message that is not JSON is answered rather than dropped`() {
        val error = service().mcpServer().answering("{not json at all").failure()

        error["code"] shouldBe JsonNum(PARSE_ERROR)
    }

    @Test
    fun `a method this does not serve is refused by name, with what it does serve`() {
        val error = service().mcpServer().answering(request(6, "resources/list")).failure()

        error["code"] shouldBe JsonNum(METHOD_NOT_FOUND)
        (error["message"] as JsonStr).value shouldContain "tools/call"
    }

    @Test
    fun `a tools call with no tool named is refused as invalid params`() {
        val error = service().mcpServer().answering(request(7, "tools/call", """{"arguments":{}}""")).failure()

        error["code"] shouldBe JsonNum(INVALID_PARAMS)
    }

    @Test
    fun `a notification is answered with silence`() {
        val answer: CompletionStage<String?> = service().mcpServer()
            .handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        answer.toCompletableFuture().join().shouldBeNull()
    }

    @Test
    fun `a batch is refused, because this answers one message with one message`() {
        val error = service().mcpServer().answering("""[{"jsonrpc":"2.0","id":1,"method":"ping"}]""").failure()

        error["code"] shouldBe JsonNum(INVALID_REQUEST)
    }

    @Test
    fun `ping is answered, so a client can tell a live session from a dead one`() {
        service().mcpServer().answering(request(8, "ping")).result() shouldBe JsonObj(emptyMap())
    }
}
