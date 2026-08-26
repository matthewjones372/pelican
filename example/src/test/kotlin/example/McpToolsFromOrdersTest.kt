package example

import io.github.matthewjones372.pelican.JsonArr
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.jsonArr
import io.github.matthewjones372.pelican.jsonObj
import io.github.matthewjones372.pelican.mcp.mcpDispatch
import io.github.matthewjones372.pelican.mcp.mcpOptions
import io.github.matthewjones372.pelican.mcp.mcpTools
import io.github.matthewjones372.pelican.mcp.toJson
import io.github.matthewjones372.pelican.renderPretty
import io.github.matthewjones372.pelican.test.golden.Golden
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * The service in this repository as a model would be told about it.
 *
 * It is the awkward case on purpose: three streamed answers, a multipart
 * upload, a raw body and a credential. What MCP cannot carry is refused rather
 * than dropped, so this test also records the one thing a reader wants to know
 * — which endpoints a model gets, and which it does not.
 */
class McpToolsFromOrdersTest {

    /**
     * The endpoints a tool call can carry. The rest are refused by name below;
     * `reindex` is hidden and never considered.
     */
    private val callable = setOf(
        "getUser", "placeOrder", "submitOrder", "placeOrderForm", "payOrder", "cancelOrder",
    )

    private val options = mcpOptions {
        include = { it.operationId in callable }
        // The credential the service requires, supplied by whatever serves the
        // tools. A model asked for an X-Api-Key would invent one.
        headers = mapOf("X-Api-Key" to "let-me-in")
    }

    private val tools = ordersSpec().mcpTools(options)

    @Test
    fun `one tool per endpoint a call can carry, under the operation's own name`() {
        tools.map { it.name } shouldContainExactly callable.toList()
    }

    @Test
    fun `an endpoint MCP cannot carry is refused by name, rather than quietly left out`() {
        val message = shouldThrow<IllegalArgumentException> {
            ordersSpec().mcpTools(mcpOptions { headers = options.headers })
        }.message.orEmpty()

        message shouldContain "streamOrders"
        withClue("a refusal that does not say what to do next is a wall") {
            message shouldContain "include"
        }
    }

    /**
     * The whole point of the schema pass underneath: `payOrder` takes a sealed
     * hierarchy, and a model handed a branch without `method` writes a payload
     * the codec then refuses.
     */
    @Test
    fun `a union body arrives with the property that says which branch it is`() {
        val defs = tools.single { it.name == "payOrder" }.inputSchema["\$defs"] as JsonObj
        val card = defs["Card"] as JsonObj
        val method = (card["properties"] as JsonObj)["method"] as JsonObj

        method["const"] shouldBe JsonStr("card")
        withClue("a schema that does not require it describes a payload that does not decode") {
            ((card["required"] as JsonArr).items) shouldContain JsonStr("method")
        }
    }

    @Test
    fun `the credential the service requires is not something the model is asked to invent`() {
        val properties = tools.single { it.name == "placeOrder" }.inputSchema["properties"] as JsonObj

        withClue("X-Api-Key is a header, and a header is not a tool argument") {
            properties.fields.keys shouldContainExactly setOf("userId", "body")
        }
    }

    /**
     * The whole path, on the service this repository runs: arguments in,
     * decoded, through the bound handler, and a declared failure back as
     * something a model can act on rather than as an exception.
     */
    @Test
    fun `a declared failure comes back as the sentence the endpoint wrote for it`() {
        val result = ordersApi().mcpDispatch(options)
            .call(
                "placeOrder",
                jsonObj {
                    "userId" to 999
                    put("body", jsonObj { "item" to "a-widget" })
                },
            )
            .toCompletableFuture()
            .join()

        result.isError.shouldBeTrue()
        // No `"detail":null`: the codecs leave a null property out, so what a
        // model reads is the sentence and the fields that carry something.
        result.text shouldBe "404 No user with that id: {\"status\":404,\"error\":\"No user 999\"}"
    }

    /**
     * The published tool list, recorded. A tool list is a contract with
     * whatever is pointed at it: a narrowed schema or a renamed argument breaks
     * a model's call exactly as a changed path breaks a caller's, and neither
     * is visible to a test that calls through the descriptions.
     */
    @Test
    fun `the tool list is the one that was reviewed`() {
        Golden().text("mcp-tools", "json", jsonArr(tools.map { it.toJson() }).renderPretty() + "\n")
    }
}
