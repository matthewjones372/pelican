package io.github.matthewjones372.pelican.pekko

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException

/**
 * Spec 0049. Pekko runs `stopScheduler()` as a termination callback, and a JDK 21
 * `ForkJoinPool.shutdown()` can interrupt the worker it runs on. Scala boxes that
 * `InterruptedException` into the future's result, so the stage a caller is waiting
 * on fails although the system did terminate.
 */
class AwaitTerminatedTest {

    /** What Pekko hands back: the stage's own wrapper around Scala's boxed exception. */
    private fun boxedInterrupt(): CompletableFuture<Unit> =
        CompletableFuture<Unit>().apply {
            completeExceptionally(ExecutionException("Boxed Exception", InterruptedException()))
        }

    @Test
    fun `an interrupt boxed by the termination callbacks is not a failure to stop`() {
        boxedInterrupt().awaitTerminated(Duration.ofSeconds(5))
    }

    @Test
    fun `the calling thread is not left carrying an interrupt it never took`() {
        boxedInterrupt().awaitTerminated(Duration.ofSeconds(5))

        Thread.currentThread().isInterrupted shouldBe false
    }

    @Test
    fun `any other failure is still thrown`() {
        val failed = CompletableFuture<Unit>().apply {
            completeExceptionally(IllegalStateException("the port is still bound"))
        }

        val thrown = shouldThrow<ExecutionException> { failed.awaitTerminated(Duration.ofSeconds(5)) }

        thrown.cause.shouldBeInstanceOf<IllegalStateException>()
    }

    @Test
    fun `a stage that never completes fails on the deadline rather than parking`() {
        shouldThrow<TimeoutException> {
            CompletableFuture<Unit>().awaitTerminated(Duration.ofMillis(50))
        }
    }

    @Test
    fun `a stage that completes is simply awaited`() {
        CompletableFuture.completedFuture(Unit).awaitTerminated(Duration.ofSeconds(5))
    }
}
