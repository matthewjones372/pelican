package example

import com.fasterxml.jackson.annotation.JsonInclude
import example.generated.ApiCallFailed
import example.generated.OrdersClient
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.ClientTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jackson.defaultMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * What arrives when something between the client and the service answers for
 * it: a proxy's HTML at a status the endpoint declared, a load balancer's
 * plain-text 502.
 */
class GeneratedClientRefusalsTest {

    private val codecs = JacksonCodecs(
        defaultMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL),
    )

    /** Answers every request the same way, whatever it was for. */
    private fun answering(status: Int, body: String, contentType: String = "text/html"): OrdersClient =
        OrdersClient(
            baseUrl = "http://orders.example",
            codecs = codecs,
            transport = ClientTransport {
                CompletableFuture.completedStage(
                    ClientResponse(status, listOf("Content-Type" to contentType), body.byteInputStream()),
                )
            },
        )

    @Test
    fun `a declared status carrying a body the codec cannot read names the call, not the codec`() {
        val html = "<html><body>404 Not Found — nginx</body></html>"

        val failed = shouldThrow<ApiCallFailed> { answering(404, html).getUser(999L) }

        failed.status shouldBe 404
        failed.method shouldBe "GET"
        failed.path shouldBe "/users/{userId}"
        failed.body shouldBe html
        withClue("the codec's own failure is what to look at next") { failed.cause.shouldNotBeNull() }
    }

    @Test
    fun `a success body the codec cannot read is refused the same way`() {
        val failed = shouldThrow<ApiCallFailed> { answering(200, "<html>hello</html>").getUser(1L) }

        failed.status shouldBe 200
        failed.path shouldBe "/users/{userId}"
        failed.body shouldContain "<html>"
    }

    @Test
    fun `a streamed call refused with a body the codec cannot read names the call too`() {
        val failed = shouldThrow<ApiCallFailed> { answering(404, "<html>gone</html>").streamOrders(999L) }

        failed.status shouldBe 404
        failed.path shouldBe "/users/{userId}/orders"
        failed.body shouldContain "gone"
    }

    @Test
    fun `an element that does not decode is refused as the call it arrived on`() {
        val frames = "data: {\"seq\":1,\"label\":\"ok\"}\n\ndata: <html>oops</html>\n\n"

        val ticks = answering(200, frames, contentType = "text/event-stream").watchOrders(1L)
        val failed = shouldThrow<ApiCallFailed> { ticks.toList() }

        failed.path shouldBe "/users/{userId}/orders/watch"
        failed.body shouldContain "oops"
    }

    @Test
    fun `a hostile body is carried in full up to the cap, and says where it was cut`() {
        val huge = "x".repeat(10_000)

        val failed = shouldThrow<ApiCallFailed> { answering(404, huge).getUser(999L) }

        failed.body shouldStartWith "xxxx"
        failed.body shouldContain "truncated"
        withClue("10,000 characters were held whole: ${failed.body.length}") {
            (failed.body.length < huge.length) shouldBe true
        }
    }

    @Test
    fun `a status nothing declared still arrives with the body it carried`() {
        val failed = shouldThrow<ApiCallFailed> { answering(502, "upstream gone", "text/plain").getUser(1L) }

        failed.status shouldBe 502
        failed.body shouldBe "upstream gone"
        withClue("nothing failed to decode, so there is nothing underneath") { failed.cause shouldBe null }
    }
}
