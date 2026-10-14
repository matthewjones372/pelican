package io.github.matthewjones372.pelican.health

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.locks.ReentrantLock

class JvmChecksTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `a disk with the space asked for passes`() {
        diskSpace(dir.toString(), minFreeBytes = 0) shouldBe Status.Pass
    }

    @Test
    fun `a disk without it fails, naming the path`() {
        diskSpace(dir.toString(), minFreeBytes = Long.MAX_VALUE)
            .shouldBeInstanceOf<Status.Fail>().output shouldContain dir.toString()
    }

    @Test
    fun `a path that is not there fails rather than reporting no space`() {
        val missing = dir.resolve("nowhere").toString()
        diskSpace(missing, minFreeBytes = 0).shouldBeInstanceOf<Status.Fail>().output shouldContain missing
    }

    @Test
    fun `heap headroom passes above the floor and fails below it`() {
        heapHeadroom(minFreeBytes = 0) shouldBe Status.Pass
        heapHeadroom(minFreeBytes = Long.MAX_VALUE).shouldBeInstanceOf<Status.Fail>().output shouldContain "heap"
    }

    @Test
    fun `no deadlock passes`() {
        noDeadlockedThreads() shouldBe Status.Pass
    }

    @Test
    fun `two threads waiting on each other's lock fail, and both are named`() {
        val first = ReentrantLock()
        val second = ReentrantLock()
        val bothHoldOne = CyclicBarrier(2)
        fun deadlock(name: String, mine: ReentrantLock, theirs: ReentrantLock): Thread =
            Thread.ofPlatform().daemon().name(name).start {
                mine.lock()
                bothHoldOne.await()
                try {
                    theirs.lockInterruptibly()
                } catch (_: InterruptedException) {
                    // How the test lets them go.
                }
            }
        val a = deadlock("deadlock-a", first, second)
        val b = deadlock("deadlock-b", second, first)

        try {
            // Both hold one lock at the barrier; the JVM sees the cycle a moment later.
            val found = generateSequence { noDeadlockedThreads().also { if (it == Status.Pass) Thread.sleep(10) } }
                .take(500)
                .firstOrNull { it != Status.Pass }

            val output = found.shouldBeInstanceOf<Status.Fail>().output
            output shouldContain "deadlock-a"
            output shouldContain "deadlock-b"
        } finally {
            a.interrupt()
            b.interrupt()
        }
    }
}
