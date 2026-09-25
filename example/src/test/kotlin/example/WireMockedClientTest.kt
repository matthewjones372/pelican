package example

import com.github.tomakehurst.wiremock.http.Fault
import example.generated.GetUserFailure
import example.generated.OrdersClient
import example.generated.PlaceOrderFailure
import io.github.matthewjones372.pelican.ApiError
import io.github.matthewjones372.pelican.In3
import io.github.matthewjones372.pelican.client.pekko.PekkoHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.of
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.test.wiremock.PelicanWireMockExtension
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import example.generated.ApiError as GeneratedApiError
import example.generated.CreateOrder as GeneratedCreateOrder
import example.generated.Outcome as GeneratedOutcome
import example.generated.User as GeneratedUser

/** The generated client against a service that is only stubs, written in the descriptions it was generated from. */
class WireMockedClientTest {

    @JvmField
    @RegisterExtension
    val orders = PelicanWireMockExtension(JacksonCodecs)

    private val client by lazy { OrdersClient(orders.baseUrl, JacksonCodecs, PekkoHttpTransport()) }

    @Test
    fun `a stubbed success reaches the client as its own type`() {
        orders.stub(getUser, 1L) answers ok(User(1, "Ada", "ada@example.com"))

        client.getUser(1L) shouldBe GeneratedOutcome.Ok(GeneratedUser(1, "Ada", "ada@example.com"))
    }

    @Test
    fun `a stubbed declared failure reaches the client as the failure it declares`() {
        orders.stub(getUser, 2L) answers noSuchUser(ApiError(404, "no such user"))

        client.getUser(2L) shouldBe
            GeneratedOutcome.Err(GetUserFailure.NotFound(GeneratedApiError(404, "no such user")))
    }

    @Test
    fun `a declared response header travels with the failure`() {
        orders.stub(placeOrder, In3(1L, "let-me-in", CreateOrder("anvil"))) answers
            throttled(ApiError(429, "slow down"), retryAfter of 30L)

        val throttledAnswer = PlaceOrderFailure.TooManyRequests(GeneratedApiError(429, "slow down"), retryAfter = 30L)
        client.placeOrder(1L, GeneratedCreateOrder("anvil"), xApiKey = "let-me-in") shouldBe
            GeneratedOutcome.Err(throttledAnswer)
        orders.verify(placeOrder, In3(1L, "let-me-in", CreateOrder("anvil")))
    }

    @Test
    fun `a reset connection reaches the client as a failure to call`() {
        orders.stub(getUser, 3L) fails Fault.CONNECTION_RESET_BY_PEER

        shouldThrowAny { client.getUser(3L) }
    }
}
