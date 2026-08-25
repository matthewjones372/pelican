package example

import com.fasterxml.jackson.annotation.JsonInclude
import example.generated.CreateOrder
import example.generated.GetUserFailure
import example.generated.OrdersClient
import example.generated.Outcome
import example.generated.PlaceOrderFailure
import io.github.matthewjones372.pelican.ClientTransport
import io.github.matthewjones372.pelican.Method
import io.github.matthewjones372.pelican.RetryPolicy
import io.github.matthewjones372.pelican.client.JavaHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jackson.defaultMapper
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.start
import io.github.matthewjones372.pelican.retryPolicy
import io.github.matthewjones372.pelican.retrying
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * The retry policy under a generated client, rather than under a test that
 * calls the transport itself.
 *
 * Retrying is a decorator over `ClientTransport` and not a line of generated
 * code, which is what makes this a constructor argument: the client is handed a
 * transport with the policy already wrapped around it, and the generated file
 * is the same file it would have been. What is asserted here is that the
 * composition holds up — that a declared failure still arrives decoded after
 * the retries are spent, and that the policy's own refusals reach a real call.
 *
 * The retrying of a server that fails and then succeeds is asserted in
 * `pelican-client-java`, where a server can be made to do exactly that.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetryingClientTest {

    private val codecs = JacksonCodecs(
        defaultMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL),
    )

    private val attempts = AtomicInteger()

    private lateinit var server: PelicanServer

    /** Counts what actually leaves, so "retried" and "not retried" are countable. */
    private val counting = ClientTransport { request ->
        attempts.incrementAndGet()
        JavaHttpTransport().send(request)
    }

    @BeforeAll
    fun setUp() {
        server = ordersApi().start(port = 0, systemName = "orders-retrying-client")
    }

    @BeforeEach
    fun reset() = attempts.set(0)

    @AfterAll
    fun tearDown() {
        server.stop().toCompletableFuture().join()
    }

    private fun clientWith(policy: RetryPolicy) = OrdersClient(server.baseUrl, codecs, counting.retrying(policy))

    /** No jitter and no waiting; what the wait would have been is core's test. */
    private fun impatient(statuses: Set<Int>) =
        retryPolicy { jitter = 0.0; initialBackoff = Duration.ZERO; this.statuses = statuses }

    @Test
    fun `a call through a retrying transport is the call it always was`() {
        val user = clientWith(impatient(setOf(503))).getUser(1L)

        user shouldBe Outcome.Ok(example.generated.User(1L, "Ada Lovelace", "ada@example.com"))
        attempts.get() shouldBe 1
    }

    /**
     * Retries spent, and the answer still decoded as what the endpoint declared
     * it could be. The policy here retries a 404, which no sensible policy
     * would: it is the cheapest way to make this server produce the same status
     * three times, and what is being asserted is what the *client* does with
     * the last of them.
     */
    @Test
    fun `the answer that ends the retrying is still the declared failure`() {
        val failed = clientWith(impatient(setOf(404))).getUser(999L) as Outcome.Err

        failed.failure.shouldBeInstanceOf<GetUserFailure.NotFound>().body.error shouldBe "No user 999"
        withClue("three attempts, and then the answer") { attempts.get() shouldBe 3 }
    }

    /**
     * The default policy will not send a POST twice, whatever came back. Only
     * the caller knows whether this service reads an idempotency key, and a
     * second order is not something a library gets to decide to place.
     */
    @Test
    fun `a POST is not sent again, even for a status that would be retried`() {
        val refused = clientWith(impatient(setOf(429))).placeOrder(
            1L,
            CreateOrder("anvil", quantity = 5_000),
            xApiKey = "let-me-in",
        ) as Outcome.Err

        refused.failure.shouldBeInstanceOf<PlaceOrderFailure.TooManyRequests>().retryAfter shouldBe 30L
        withClue("the order was placed more than once") { attempts.get() shouldBe 1 }
    }

    /** And it is sent twice where the caller has said that this POST may be. */
    @Test
    fun `unless the caller has said this one may be`() {
        val told = retryPolicy {
            jitter = 0.0
            initialBackoff = Duration.ZERO
            statuses = setOf(429)
            methods = setOf(Method.POST)
            maxAttempts = 2
            // This server asks for thirty seconds, and a test that honoured it
            // would take thirty seconds. What happens to a `Retry-After` this
            // long is the next test's subject.
            honourRetryAfter = false
        }

        clientWith(told).placeOrder(1L, CreateOrder("anvil", quantity = 5_000), xApiKey = "let-me-in")

        attempts.get() shouldBe 2
    }

    /**
     * The other half of honouring `Retry-After`: a server that asks for longer
     * than the policy will wait is answered by giving up rather than by
     * retrying sooner than it asked.
     */
    @Test
    fun `a Retry-After beyond the cap ends the retrying rather than shortening the wait`() {
        val patient = retryPolicy {
            jitter = 0.0
            initialBackoff = Duration.ZERO
            statuses = setOf(429)
            methods = setOf(Method.POST)
        }

        clientWith(patient).placeOrder(1L, CreateOrder("anvil", quantity = 5_000), xApiKey = "let-me-in")

        withClue("the server asked for 30s and the cap is 10s, so nothing should have been sent again") {
            attempts.get() shouldBe 1
        }
    }
}
