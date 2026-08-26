package example.mcp

import example.ordersApi
import io.github.matthewjones372.pelican.mcp.McpOptions
import io.github.matthewjones372.pelican.mcp.mcpOptions
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.mcp.routeWithMcp
import io.github.matthewjones372.pelican.pekko.start

/**
 * The Orders service with its tools served beside its endpoints: the same
 * descriptions, the same handlers, one more route.
 *
 * `./gradlew :example:runMcp`, then point a client at `http://127.0.0.1:8080/mcp`.
 */
fun main(args: Array<String>) {
    val server = ordersWithTools(port = args.firstOrNull()?.toInt() ?: DEFAULT_PORT)
    println(
        """
        |Orders API listening on ${server.baseUrl}
        |
        |  MCP  ${server.baseUrl}/mcp    (Streamable HTTP; POST one JSON-RPC message)
        |
        |  curl -s ${server.baseUrl}/mcp -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
        |
        |Six of the thirteen endpoints are tools. The ones that stream, the
        |multipart upload and the raw body are left out by name below: a tool
        |call has one result, and what MCP cannot carry is refused rather than
        |half-served.
        |
        |Ctrl-C to stop.
        """.trimMargin(),
    )
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    server.block()
}

/** The service and its tools on one port. Written once, run by `main` and by the test. */
fun ordersWithTools(port: Int): PelicanServer = ordersApi().start(port = port) { system ->
    routeWithMcp(system, ordersTools)
}

/**
 * Which of the endpoints a model is told about, and the credential supplied on
 * its behalf.
 *
 * Both halves are decisions somebody has to make: the Orders service streams
 * three of its answers, takes a multipart upload and a raw body, and requires
 * an `X-Api-Key` — and a model asked for a credential invents one.
 */
val ordersTools: McpOptions = mcpOptions {
    include = { it.operationId in callable }
    headers = mapOf("X-Api-Key" to (System.getenv("ORDERS_API_KEY") ?: "let-me-in"))
}

/** The endpoints a tool call can carry, named rather than derived: see the refusals in docs/mcp.md. */
private val callable = setOf(
    "getUser", "placeOrder", "submitOrder", "placeOrderForm", "payOrder", "cancelOrder",
)

/** What every example binds to unless told otherwise; `--args=8081` moves it. */
private const val DEFAULT_PORT = 8080
