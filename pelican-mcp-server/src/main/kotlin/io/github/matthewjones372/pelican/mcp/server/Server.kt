package io.github.matthewjones372.pelican.mcp.server

import io.github.matthewjones372.pelican.Api
import io.github.matthewjones372.pelican.mcp.McpOptions
import io.github.matthewjones372.pelican.mcp.mcpOptions
import java.io.InputStream
import java.io.OutputStream

/**
 * Serves this API's tools over stdio — one JSON-RPC message per line in, one
 * per line out — until the input ends.
 *
 * Blocking, and the calling thread is the session: a message is answered before
 * the next is read. That is the order a client's ids come back in, and a local
 * tool call is not what a service is scaled for.
 *
 * **Nothing else may write to [output].** It is the transport, so a `println`
 * in a handler is a line the client tries to parse as a message; a stdio server
 * logs to stderr. [input] and [output] are parameters for the same reason the
 * ports are elsewhere — a session over two in-memory streams is a test, and a
 * session over two pipes is a subprocess somebody launched.
 */
fun mcpServe(
    api: Api,
    options: McpOptions = mcpOptions(),
    input: InputStream = System.`in`,
    output: OutputStream = System.out,
) {
    val server = api.mcpServer(options)
    val writer = output.bufferedWriter()
    input.bufferedReader()
        .lineSequence()
        .filter { it.isNotBlank() }
        .forEach { line ->
            server.handle(line).toCompletableFuture().join()?.let { answer ->
                writer.write(answer)
                writer.newLine()
                // Per message rather than per buffer: the client is waiting on
                // this one, and a buffer flushed when it happens to fill is a
                // session that hangs.
                writer.flush()
            }
        }
}
