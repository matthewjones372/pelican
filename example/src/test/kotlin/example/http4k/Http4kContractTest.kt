package example.http4k

import dev.pelican.http4k.PelicanServer
import dev.pelican.http4k.start
import dev.pelican.jackson.JacksonCodecs
import dev.pelican.test.ApiClient
import dev.pelican.test.apiClient
import example.ClientContractTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The whole contract suite, against the other backend.
 *
 * Not a copy of it: [ClientContractTest] is the same class the Pekko transports
 * run, and every assertion in it is written against endpoint *descriptions*, so
 * running it here asks whether two independent interpreters of those
 * descriptions behave identically. Path templates, parameter names, statuses,
 * framings, declared failures — all of it has to match, or this fails.
 *
 * The client needs nothing from either backend: it builds requests from the
 * descriptions and sends them over a socket with the JDK's own HTTP client.
 */
class Http4kContractTest : ClientContractTest() {
    private lateinit var server: PelicanServer

    override fun open(): ApiClient {
        server = ordersApi().start(port = 0)
        return apiClient(server.baseUrl, JacksonCodecs)
    }

    override fun shutDown() {
        app.close()
        server.stop()
    }

    /**
     * The same delivery-timing assertion the in-memory Pekko suite makes, made
     * here over a socket: `collect` flattens a stream and so cannot tell when
     * an element arrived, and the SSE endpoint produces one every 100ms. A
     * buffered response would deliver the first at the same moment as the last.
     */
    @Test
    fun `elements are delivered as produced, not buffered until the end`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("${server.baseUrl}/users/1/orders/watch?limit=8")).build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        assertEquals(200, response.statusCode())

        val start = System.nanoTime()
        val reader = response.body().bufferedReader()

        // The first frame is `event: order`, and reaching it means bytes have
        // arrived — which is the question being asked.
        reader.readLine()
        val firstMs = (System.nanoTime() - start) / 1_000_000

        while (reader.readLine() != null) Unit
        val totalMs = (System.nanoTime() - start) / 1_000_000

        assertTrue(totalMs >= 600, "stream finished suspiciously fast: ${totalMs}ms")
        assertTrue(
            firstMs < totalMs / 2,
            "first element at ${firstMs}ms of ${totalMs}ms — looks buffered, not streamed",
        )
    }
}
