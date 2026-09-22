package io.github.matthewjones372.pelican.client.pekko

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.pekko.stream.StreamTcpException
import org.apache.pekko.stream.SubscriptionWithCancelException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletionException

/**
 * What Pekko's own failures look like by the time a caller sees them.
 *
 * Spec 0052: a `NonFailureCancellation` was crossing the SPI as itself — an
 * internal Pekko object with no stack and no message, which reads as neither a
 * refusal nor an accident, so `RetryPolicy`'s default `failures` (`it is
 * IOException`) would not retry it and a caller had nothing to match on.
 */
class CancellationAsIoTest {

    /** The real singleton: a Scala `case object`, so `MODULE$` is the only instance there is. */
    private val cancelled: Throwable = SubscriptionWithCancelException.`StageWasCompleted$`.`MODULE$`

    @Test
    fun `a cancelled exchange crosses as an IOException, keeping Pekko's own as the cause`() {
        val crossed = asIo(cancelled)

        crossed.shouldBeInstanceOf<IOException>()
        crossed.cause shouldBe cancelled
    }

    /**
     * The cancellation is a Scala `object` mixing in `NoStackTrace`: no frames,
     * and no message either. Handing on a null would name nothing at all.
     */
    @Test
    fun `it carries a message of its own, since the cancellation has none`() {
        cancelled.message shouldBe null

        asIo(cancelled).message
            .shouldBeInstanceOf<String>()
            .shouldContain("StageWasCompleted")
    }

    /** As it arrives from a `CompletionStage`, which boxes it. */
    @Test
    fun `it is unwrapped from the CompletionException a stage wraps it in`() {
        val crossed = asIo(CompletionException(cancelled))

        crossed.shouldBeInstanceOf<IOException>()
        crossed.cause shouldBe cancelled
    }

    /** The case that was already covered, unchanged by the one beside it. */
    @Test
    fun `a connection refused or reset still crosses as an IOException`() {
        val refused = StreamTcpException("Connection refused")

        val crossed = asIo(refused)

        crossed.shouldBeInstanceOf<IOException>()
        crossed.message shouldBe "Connection refused"
        crossed.cause shouldBe refused
    }

    /** Anything that is already what a caller should see is handed on untouched. */
    @Test
    fun `a failure that is not Pekko's is left exactly as it was`() {
        val mine = IllegalStateException("the handler said so")

        asIo(mine) shouldBe mine
        asIo(CompletionException(mine)) shouldBe mine
    }
}
