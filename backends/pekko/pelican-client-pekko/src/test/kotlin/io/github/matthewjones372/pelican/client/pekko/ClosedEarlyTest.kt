package io.github.matthewjones372.pelican.client.pekko

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.matthewjones372.pelican.ClientRequest
import io.github.matthewjones372.pelican.Method
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.net.InetSocketAddress
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * A response the caller closes before its end, then a `POST` on the same transport (spec 0052).
 *
 * Pekko's pool reads a cancelled response entity as a finished one and offers the slot to the next request while
 * the connection under it is still being torn down. A non-idempotent request handed that slot is not retried, and
 * fails with `StageWasCompleted`. Several callers at once is what makes the window wide enough to hit.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClosedEarlyTest {

    private val handlers = Executors.newVirtualThreadPerTaskExecutor()

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = handlers
        createContext("/ticks") { exchange -> ticks(exchange) }
        createContext("/orders") { exchange ->
            exchange.requestBody.readAllBytes()
            val bytes = "placed".toByteArray()
            exchange.sendResponseHeaders(OK, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val base = "http://127.0.0.1:${server.address.port}"
    private val transport = PekkoHttpTransport()

    @AfterAll
    fun tearDown() {
        server.stop(0)
        handlers.shutdownNow()
    }

    /** A short stream, chunked and spaced, so a caller who reads one tick has closed it before its end. */
    private fun ticks(exchange: HttpExchange) {
        exchange.sendResponseHeaders(OK, 0)
        exchange.responseBody.use { out ->
            repeat(TICKS) { i ->
                out.write("tick $i\n".toByteArray())
                out.flush()
                Thread.sleep(TICK_MILLIS)
            }
        }
    }

    private fun send(request: ClientRequest) = transport.send(request).toCompletableFuture().join()

    @Test
    fun `a post sent after a response was closed midway is answered`() {
        val failures = ConcurrentLinkedQueue<String>()

        val callers = (1..CALLERS).map { _ ->
            Thread.ofPlatform().start {
                repeat(ROUNDS) { _ ->
                    send(ClientRequest(Method.GET, "$base/ticks")).body.use { body -> body.read(ByteArray(4)) }
                    try {
                        val placed = send(
                            ClientRequest(
                                Method.POST,
                                "$base/orders",
                                listOf("Content-Type" to "application/json"),
                                ClientRequest.Body.Text("""{"item":"anvil"}"""),
                            ),
                        )
                        placed.text() shouldBe "placed"
                    } catch (e: CompletionException) {
                        failures.add(generateSequence<Throwable>(e) { t -> t.cause }.joinToString(" <- ") { t -> "$t" })
                    }
                }
            }
        }
        callers.forEach { it.join() }

        withClue("${failures.size} of ${CALLERS * ROUNDS} posts failed, first: ${failures.firstOrNull()}") {
            failures.shouldBeEmpty()
        }
    }

    private companion object {
        const val OK = 200
        const val TICKS = 5
        const val TICK_MILLIS = 10L
        const val CALLERS = 6
        const val ROUNDS = 40
    }
}
