package io.github.matthewjones372.pelican.pekko

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException

/**
 * `toStrict` gives up on a body that stops arriving, and the caller is the one
 * who can act on that: 408, as Ktor already answers. It used to fall through
 * the interpreter's `exceptionally` as a throwable nobody described, so one
 * `strictBodyTimeoutMillis` meant a 408 on one backend and a 500 on this one.
 *
 * The predicate rather than the socket. Stalling a real body and waiting for a
 * real answer is a race against whatever else the machine is doing.
 */
class ReadTimeoutTest {

    @Test
    fun `a timeout is recognised`() {
        isReadTimeout(TimeoutException("took too long")) shouldBe true
    }

    @Test
    fun `and so is one a stage has wrapped`() {
        isReadTimeout(CompletionException(TimeoutException("took too long"))) shouldBe true
    }

    @Test
    fun `and one wrapped twice, which is what the entity read produces`() {
        isReadTimeout(CompletionException(RuntimeException(TimeoutException("slow")))) shouldBe true
    }

    @Test
    fun `anything else keeps the 500 it had`() {
        isReadTimeout(IOException("connection reset")) shouldBe false
        isReadTimeout(IllegalStateException("a bug")) shouldBe false
    }
}
