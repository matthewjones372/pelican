package io.github.matthewjones372.pelican.gannet

import com.sun.management.ThreadMXBean
import io.github.matthewjones372.gannet.RequestBody
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.io.IOException
import java.lang.management.ManagementFactory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class SweeperTest {

    /** A body that only records being failed. */
    private class Watched : RequestBody {
        val fails = AtomicInteger()
        val cause = AtomicReference<IOException?>()

        override val length: Long? = null

        override fun readAll(limit: Long): ByteArray = ByteArray(0)

        override fun readAll(): ByteArray = ByteArray(0)

        override fun read(into: ByteArray, offset: Int, length: Int): Int = -1

        override fun read(into: ByteArray, offset: Int, length: Int, timeout: Duration): Int = -1

        override fun fail(cause: IOException) {
            this.cause.set(cause)
            fails.incrementAndGet()
        }
    }

    private val second = TimeUnit.SECONDS.toNanos(1)

    @Test
    fun `an overdue deadline fails its body once, with the exception the read is handed back`() {
        val sweeper = Sweeper()
        val body = Watched()
        val deadline = sweeper.arm(body, timeoutMillis = 1_000, now = 0)

        sweeper.sweep(now = second) shouldBe 0
        sweeper.sweep(now = 2 * second) shouldBe 0

        body.fails.get() shouldBe 1
        body.cause.get().shouldBeInstanceOf<TimedOut>()
        deadline.settle() shouldBeSameInstanceAs body.cause.get()
    }

    @Test
    fun `a deadline is kept until it is due and never fires early`() {
        val sweeper = Sweeper()
        val body = Watched()
        sweeper.arm(body, timeoutMillis = 1_000, now = 0)

        sweeper.sweep(now = second - 1) shouldBe 1
        body.fails.get() shouldBe 0
        sweeper.sweep(now = second) shouldBe 0
        body.fails.get() shouldBe 1
    }

    @Test
    fun `a settled deadline is dropped by the next sweep and never fires`() {
        val sweeper = Sweeper()
        val bodies = List(10_000) { Watched() }
        bodies.forEach { sweeper.arm(it, timeoutMillis = 1_000, now = 0).settle().shouldBeNull() }

        withClue("nothing settled is held past one sweep") { sweeper.sweep(now = 0) shouldBe 0 }
        sweeper.sweep(now = 10 * second) shouldBe 0
        bodies.sumOf { it.fails.get() } shouldBe 0
    }

    @Test
    fun `deadlines still armed are carried from sweep to sweep while settled ones leave`() {
        val sweeper = Sweeper()
        val armed = List(100) { sweeper.arm(Watched(), timeoutMillis = 1_000, now = 0) }
        sweeper.sweep(now = 0) shouldBe 100

        armed.take(60).forEach { it.settle() }
        sweeper.arm(Watched(), timeoutMillis = 1_000, now = 0)

        sweeper.sweep(now = 0) shouldBe 41
    }

    @Test
    fun `arming and settling a deadline allocates no exception`() {
        val sweeper = Sweeper()
        val body = Watched()
        val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val rounds = 100_000
        repeat(rounds) { sweeper.arm(body, timeoutMillis = 1_000).settle() }
        sweeper.sweep(System.nanoTime())

        val before = threads.currentThreadAllocatedBytes
        repeat(rounds) { sweeper.arm(body, timeoutMillis = 1_000).settle() }
        val perDeadline = (threads.currentThreadAllocatedBytes - before) / rounds

        // A filled-in IOException alone is several hundred bytes; a deadline is one small object.
        withClue("$perDeadline bytes per deadline") { perDeadline shouldBeLessThan 64L }
        sweeper.sweep(System.nanoTime()) shouldBe 0
    }

    @Test
    fun `readers settling while a sweeper fires each deadline exactly once or not at all`() {
        val sweeper = Sweeper()
        val readers = 8
        val each = 20_000
        val go = CountDownLatch(1)
        val done = AtomicBoolean()
        val outcomes = List(readers) { AtomicReference<List<Pair<Watched, IOException?>>>(emptyList()) }

        val sweeping = Thread.ofPlatform().start {
            go.await()
            while (!done.get()) sweeper.sweep(System.nanoTime())
        }
        val workers = outcomes.mapIndexed { i, outcome ->
            Thread.ofPlatform().start {
                go.await()
                // Half are due at once, so the sweeper races the reader for them; half are not due for a minute.
                outcome.set(
                    List(each) { n ->
                        val body = Watched()
                        val timeout = if ((n + i) % 2 == 0) 0L else 60_000L
                        body to sweeper.arm(body, timeout).settle()
                    },
                )
            }
        }
        go.countDown()
        workers.forEach { it.join() }
        done.set(true)
        sweeping.join()

        withClue("every deadline settled or fired, so none is left") { sweeper.sweep(System.nanoTime()) shouldBe 0 }
        outcomes.flatMap { it.get() }.forEach { (body, fired) ->
            if (fired == null) {
                body.fails.get() shouldBe 0
            } else {
                body.fails.get() shouldBe 1
                body.cause.get() shouldBeSameInstanceAs fired
            }
        }
    }
}
