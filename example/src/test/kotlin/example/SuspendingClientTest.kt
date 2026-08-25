package example

import com.fasterxml.jackson.annotation.JsonInclude
import example.generated.suspending.CreateOrder
import example.generated.suspending.GetUserFailure
import example.generated.suspending.OrderStatus
import example.generated.suspending.OrdersClient
import example.generated.suspending.Outcome
import example.generated.suspending.PlaceOrderFailure
import example.generated.suspending.User
import io.github.matthewjones372.pelican.ClientResponse
import io.github.matthewjones372.pelican.ClientTransport
import io.github.matthewjones372.pelican.client.JavaHttpTransport
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.jackson.defaultMapper
import io.github.matthewjones372.pelican.pekko.PelicanServer
import io.github.matthewjones372.pelican.pekko.start
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The other call shape, against the same server the blocking suite runs
 * against.
 *
 * `GeneratedKotlinClientTest` calls `example.generated.OrdersClient`; this calls
 * `example.generated.suspending.OrdersClient`, which the build generates from
 * the same `ordersSpec()` with `callStyle = "suspending"`. The endpoints, the
 * method names, the parameters and the sealed failures are the same ones, and
 * `SuspendingClientTest` in pelican-codegen asserts that they are. So what is
 * worth asserting here is what the two shapes disagree about: that a call can
 * be made from a coroutine, that many can be outstanding at once without a
 * thread each, and that cancelling the coroutine cancels the call.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SuspendingClientTest {

    private val codecs = JacksonCodecs(
        defaultMapper().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL),
    )

    private lateinit var server: PelicanServer
    private lateinit var client: OrdersClient

    @BeforeAll
    fun setUp() {
        server = ordersApi().start(port = 0, systemName = "orders-suspending-client")
        // Named rather than defaulted: this module carries both the JDK and the
        // Pekko adapters, so `ClientTransport.default()` has two providers to
        // choose between and rightly refuses to.
        client = OrdersClient(server.baseUrl, codecs, JavaHttpTransport())
    }

    @AfterAll
    fun tearDown() {
        server.stop().toCompletableFuture().join()
    }

    @Test
    fun `a call answers with the declared type, from inside a coroutine`() = runBlocking {
        client.getUser(1L) shouldBe Outcome.Ok(User(1L, "Ada Lovelace", "ada@example.com"))
    }

    @Test
    fun `a declared failure is the same failure it is on the blocking client`() = runBlocking {
        val failed = client.getUser(999L) as Outcome.Err

        failed.failure.shouldBeInstanceOf<GetUserFailure.NotFound>().body.error shouldBe "No user 999"
    }

    @Test
    fun `a declared failure carrying a header still carries it`() = runBlocking {
        val refused = client.placeOrder(
            1L,
            CreateOrder("anvil", quantity = 5_000),
            xApiKey = "let-me-in",
        ) as Outcome.Err

        refused.failure.shouldBeInstanceOf<PlaceOrderFailure.TooManyRequests>().retryAfter shouldBe 30L
    }

    /**
     * A streamed response is read as it arrives here as it is anywhere else,
     * and reading it is a socket read — so it happens on `Dispatchers.IO`,
     * which is what the generated file's own comment tells a caller to do.
     */
    @Test
    fun `a streamed response is iterated where a blocking read belongs`() = runBlocking {
        val stream = client.streamOrders(1L, limit = 3, status = OrderStatus.SHIPPED)

        val orders = withContext(Dispatchers.IO) { (stream as Outcome.Ok).value.toList() }

        orders.size shouldBe 3
        orders.forEach { it.status shouldBe OrderStatus.SHIPPED }
    }

    /**
     * Twenty calls outstanding at once on a dispatcher with one thread.
     *
     * This is the argument for the shape: a blocking client needs twenty
     * threads to have twenty calls in flight, and here the one thread is free
     * between the send and the answer. Asserted by the answers rather than by a
     * clock, because a timing assertion on a loaded machine is a coin toss —
     * and a client that could not do this would not finish at all, since the
     * one thread would be sitting in the first call.
     */
    @Test
    fun `many calls are in flight at once without a thread each`() = runBlocking {
        val answers = withContext(oneThread) {
            (1..CALLS_AT_ONCE).map { async { client.getUser(1L) } }.awaitAll()
        }

        answers.forEach { it shouldBe Outcome.Ok(User(1L, "Ada Lovelace", "ada@example.com")) }
    }

    /**
     * A coroutine that gives up takes the call with it.
     *
     * The transport here answers nothing at all, which is the only reliable way
     * to still be waiting when the cancellation arrives; that a cancelled stage
     * then stops a real exchange is `pelican-client-java`'s own test, against a
     * real `HttpClient`. What this asserts is the half the generated file is
     * responsible for — that the cancellation reaches the stage rather than
     * being lost between the coroutine and the transport.
     */
    @Test
    fun `cancelling the coroutine cancels the exchange the call was waiting on`() = runBlocking {
        val exchange = CompletableFuture<ClientResponse>()
        val asked = CompletableFuture<Boolean>()
        val stuck = OrdersClient(
            server.baseUrl,
            codecs,
            ClientTransport {
                asked.complete(true)
                exchange
            },
        )

        // Somewhere other than this coroutine's own thread, so that waiting for
        // the call to start does not stop it from starting.
        val call = launch(Dispatchers.Default) { stuck.getUser(1L) }
        asked.get(FIVE_SECONDS, TimeUnit.SECONDS) shouldBe true

        call.cancelAndJoin()

        withClue("the coroutine ended and left the request it was waiting on running") {
            exchange.isCancelled shouldBe true
        }
    }

    private companion object {
        const val FIVE_SECONDS = 5L
        const val CALLS_AT_ONCE = 20

        /** One thread, so that twenty calls in flight is a claim about the client. */
        val oneThread = Dispatchers.Default.limitedParallelism(1)
    }
}
