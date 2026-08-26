package io.github.matthewjones372.pelican

import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith

/**
 * What the policy decides, and what the decorator does with the decision.
 *
 * The decisions are asserted here against no server at all, because they are
 * arithmetic and a set membership; that a retry actually reaches a socket, and
 * that a body drained by the first attempt is sent whole by the second, is
 * asserted in `pelican-client-pekko` against a server that fails and then
 * succeeds.
 */
class RetryTest {

    private val get = ClientRequest(Method.GET, "https://orders.internal/orders/1")
    private val post = ClientRequest(Method.POST, "https://orders.internal/orders")

    /** No jitter, so a wait is the number the curve says rather than a range. */
    private val exact = retryPolicy { jitter = 0.0 }

    private fun response(status: Int, headers: List<Pair<String, String>> = emptyList()) =
        ClientResponse(status, headers, ByteArrayInputStream(ByteArray(0)))

    // ------------------------------------------------------------ the policy

    /** The numbers the class documentation argues for, pinned against the block. */
    @Test
    fun `a block that says nothing leaves every setting where it has always been`() {
        val policy = retryPolicy()

        policy.maxAttempts shouldBe 3
        policy.initialBackoff shouldBe Duration.ofMillis(100)
        policy.backoffMultiplier shouldBe 2.0
        policy.maxBackoff shouldBe Duration.ofSeconds(2)
        policy.jitter shouldBe 0.5
        policy.statuses shouldBe TRANSIENT_STATUSES
        policy.methods shouldBe IDEMPOTENT_METHODS
        policy.retryStreamedBodies shouldBe false
        policy.honourRetryAfter shouldBe true
        policy.retryAfterCap shouldBe Duration.ofSeconds(10)
        policy.failures(IOException("refused")) shouldBe true
        policy.failures(IllegalStateException("bug")) shouldBe false
    }

    @Test
    fun `a builder written into after the policy is built cannot change it`() {
        lateinit var escaped: RetryPolicyBuilder
        val policy = retryPolicy {
            escaped = this
            maxAttempts = 2
        }

        escaped.maxAttempts = 9
        escaped.honourRetryAfter = false

        policy.maxAttempts shouldBe 2
        policy.honourRetryAfter shouldBe true
    }

    @Test
    fun `a policy retries a transient status on an idempotent method`() {
        exact.retryDelay(get, response(503), attempt = 1) shouldBe Duration.ofMillis(100)
        exact.retryDelay(get, response(503), attempt = 2) shouldBe Duration.ofMillis(200)
    }

    @Test
    fun `and stops once the attempts it was given are spent`() {
        exact.retryDelay(get, response(503), attempt = 3).shouldBeNull()
    }

    @Test
    fun `a 500 is not transient, whatever else it is`() {
        exact.retryDelay(get, response(500), attempt = 1).shouldBeNull()
    }

    @Test
    fun `nor is any status the endpoint might have meant`() {
        listOf(200, 201, 400, 401, 404, 409, 422).forEach { status ->
            exact.retryDelay(get, response(status), attempt = 1).shouldBeNull()
        }
    }

    @Test
    fun `a method that is not idempotent is never sent twice unless it was named`() {
        exact.retryDelay(post, response(503), attempt = 1).shouldBeNull()

        val told = retryPolicy { jitter = 0.0; methods = IDEMPOTENT_METHODS + Method.POST }
        told.retryDelay(post, response(503), attempt = 1) shouldBe Duration.ofMillis(100)
    }

    @Test
    fun `a streamed body is left alone until its owner says it can be opened again`() {
        val streamed = ClientRequest(
            Method.PUT,
            "https://orders.internal/orders/1/attachment",
            body = ClientRequest.Body.Streaming { ByteArrayInputStream("anvil".toByteArray()) },
        )

        exact.retryDelay(streamed, response(503), attempt = 1).shouldBeNull()
        retryPolicy { jitter = 0.0; retryStreamedBodies = true }
            .retryDelay(streamed, response(503), attempt = 1) shouldBe Duration.ofMillis(100)
    }

    @Test
    fun `a Retry-After longer than the curve is what gets waited`() {
        val asked = exact.retryDelay(get, response(429, listOf("Retry-After" to "2")), attempt = 1)
        asked shouldBe Duration.ofSeconds(2)
    }

    @Test
    fun `a Retry-After shorter than the curve does not shorten it`() {
        val asked = exact.retryDelay(get, response(429, listOf("Retry-After" to "0")), attempt = 1)
        asked shouldBe Duration.ofMillis(100)
    }

    @Test
    fun `a Retry-After beyond the cap ends the retrying rather than the waiting`() {
        exact.retryDelay(get, response(503, listOf("Retry-After" to "600")), attempt = 1).shouldBeNull()
    }

    @Test
    fun `a Retry-After in the date form is read as though it were absent`() {
        val header = listOf("Retry-After" to "Wed, 21 Oct 2015 07:28:00 GMT")
        exact.retryDelay(get, response(503, header), attempt = 1) shouldBe Duration.ofMillis(100)
    }

    @Test
    fun `the curve is capped, so a policy with more attempts does not wait for ever`() {
        val patient = retryPolicy { maxAttempts = 10; jitter = 0.0 }
        patient.retryDelay(get, response(503), attempt = 9) shouldBe Duration.ofSeconds(2)
    }

    @Test
    fun `jitter spreads a wait over the fraction it was given`() {
        val waits = (1..50).map {
            retryPolicy().retryDelay(get, response(503), attempt = 1).shouldNotBeNull().toMillis()
        }

        waits.forEach {
            it shouldBeGreaterThanOrEqual 50L
            it shouldBeLessThanOrEqual 100L
        }
    }

    @Test
    fun `an IOException is worth another attempt and a programming error is not`() {
        exact.retryDelay(get, IOException("connection reset"), attempt = 1) shouldBe Duration.ofMillis(100)
        exact.retryDelay(get, IllegalStateException("no codec"), attempt = 1).shouldBeNull()
    }

    @Test
    fun `a policy of one attempt is a policy that never retries`() {
        val once = retryPolicy { maxAttempts = 1 }
        once.retryDelay(get, response(503), attempt = 1).shouldBeNull()
        once.retryDelay(get, IOException("reset"), attempt = 1).shouldBeNull()
    }

    // --------------------------------------------------------- the decorator

    @Test
    fun `the decorator sends again and hands back the attempt that worked`() {
        val attempts = AtomicInteger()
        val transport = ClientTransport {
            val number = attempts.incrementAndGet()
            CompletableFuture.completedFuture(if (number < 3) response(503) else response(200))
        }

        val answered = transport.retrying(retryPolicy { jitter = 0.0; initialBackoff = Duration.ZERO })
            .send(get)
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS)

        answered.status shouldBe 200
        attempts.get() shouldBe 3
    }

    @Test
    fun `a response it decided against keeping is closed rather than dropped`() {
        val discarded = Closing()
        val attempts = AtomicInteger()
        val transport = ClientTransport {
            val number = attempts.incrementAndGet()
            CompletableFuture.completedFuture(
                if (number == 1) ClientResponse(503, emptyList(), discarded) else response(200),
            )
        }

        transport.retrying(retryPolicy { jitter = 0.0; initialBackoff = Duration.ZERO })
            .send(get)
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS)

        discarded.closed shouldBe true
    }

    @Test
    fun `a failure nothing here retries reaches the caller as what was raised`() {
        val transport = ClientTransport {
            CompletableFuture.failedFuture(IllegalStateException("no codec for Order"))
        }

        val failed = assertFailsWith<CompletionException> {
            transport.retrying().send(get).toCompletableFuture().join()
        }

        failed.cause.shouldBeInstanceOf<IllegalStateException>()
    }

    @Test
    fun `the last attempt's failure is the one the caller is given`() {
        val attempts = AtomicInteger()
        val transport = ClientTransport {
            attempts.incrementAndGet()
            CompletableFuture.failedFuture(IOException("connection reset"))
        }

        val failed = assertFailsWith<CompletionException> {
            transport.retrying(retryPolicy { jitter = 0.0; initialBackoff = Duration.ZERO })
                .send(get)
                .toCompletableFuture()
                .join()
        }

        failed.cause.shouldBeInstanceOf<IOException>()
        attempts.get() shouldBe 3
    }

    @Test
    fun `cancelling what the caller holds cancels the exchange under it`() {
        val inFlight = CompletableFuture<ClientResponse>()
        val transport = ClientTransport { inFlight }

        val answer = transport.retrying().send(get).toCompletableFuture()
        answer.cancel(true) shouldBe true

        inFlight.isCancelled shouldBe true
    }

    @Test
    fun `and stops the retry that was going to follow it`() {
        val attempts = AtomicInteger()
        val transport = ClientTransport {
            attempts.incrementAndGet()
            CompletableFuture.completedFuture(response(503))
        }

        // Long enough that the retry is still waiting when the cancellation
        // arrives, and short enough that a mistake here shows up as a failure
        // rather than as a slow suite.
        val waiting = retryPolicy { jitter = 0.0; initialBackoff = Duration.ofMillis(500) }
        val answer = transport.retrying(waiting).send(get).toCompletableFuture()

        answer.cancel(true)
        Thread.sleep(1_000)

        attempts.get() shouldBe 1
    }

    /** A body that says whether whoever took it closed it. */
    private class Closing : InputStream() {
        var closed = false
            private set

        override fun read(): Int = -1

        override fun close() {
            closed = true
        }
    }
}
