package example

import io.github.matthewjones372.pelican.JsonNum
import io.github.matthewjones372.pelican.JsonObj
import io.github.matthewjones372.pelican.JsonStr
import io.github.matthewjones372.pelican.mcp.mcpTools
import io.github.matthewjones372.pelican.operationName
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The service in this repository as a model would be told about it. The claims
 * are about agreement: a tool per operation, under the name the document and
 * the generated client already use, and every constraint the server enforces
 * visible in the schema a model reads.
 */
class McpToolsFromOrdersTest {

    private val tools = ordersSpec().mcpTools()

    @Test
    fun `one tool per described endpoint, under the operation's own name`() {
        // `reindex` is hidden: still routed, still callable, and written down
        // nowhere — which a tool list is.
        tools.map { it.name } shouldContainExactly allEndpoints.filterNot { it.hidden }.map { it.operationName }
    }

    @Test
    fun `a refinement the server enforces is a constraint the model is given`() {
        // `limit` is queryParam("limit", IntCodec.between(1, 100)): the server
        // answers 400 below 1, and a model that never sends 0 never sees it.
        val limit = tools.single { it.name == "streamOrders" }.inputSchema.property("limit")

        limit["minimum"] shouldBe JsonNum(1)
        limit["maximum"] shouldBe JsonNum(100)
        limit["description"] shouldBe JsonStr("Maximum rows to stream")
    }

    @Test
    fun `the credential the service requires is not something the model is asked to invent`() {
        val placeOrder = tools.single { it.name == "placeOrder" }
        val properties = placeOrder.inputSchema["properties"] as JsonObj

        withClue("X-Api-Key is a header, and a header is not a tool argument") {
            properties.fields.keys shouldContainExactly setOf("userId", "body")
        }
    }
}

private fun JsonObj.property(name: String): JsonObj = (this["properties"] as JsonObj)[name] as JsonObj
