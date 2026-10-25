package io.github.matthewjones372.pelican

import io.github.matthewjones372.pelican.spi.succeeded
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class SucceededStageTest {

    @Test
    fun `a future that has already succeeded is read in place, null value and all`() {
        val done = CompletableFuture.completedFuture<String?>(null)
        done.succeeded() shouldBeSameInstanceAs done
        done.succeeded()?.resultNow() shouldBe null
    }

    @Test
    fun `a stage still running, or failed, is chained as before`() {
        CompletableFuture<String>().succeeded().shouldBeNull()
        CompletableFuture.failedFuture<String>(IllegalStateException("no")).succeeded().shouldBeNull()
    }

    @Test
    fun `the minimal stage completedStage returns is never queried, since every query on it throws`() {
        CompletableFuture.completedStage("done").succeeded().shouldBeNull()
    }
}
