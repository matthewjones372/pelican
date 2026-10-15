package io.github.matthewjones372.pelican.jetty

import io.kotest.assertions.fail
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

/** The writer against a sink whose first write, the headers, completes only when the test says. */
class FramesGatheredTest {

    private class HeldSink : Content.Sink {
        val writes = LinkedBlockingQueue<String>()
        private val held = CompletableFuture<Callback>()

        override fun write(last: Boolean, byteBuffer: ByteBuffer, callback: Callback) {
            if (last) return callback.succeeded()
            writes.add(UTF_8.decode(byteBuffer).toString())
            if (held.isDone) callback.succeeded() else held.complete(callback)
        }

        fun release() = held.get(WAIT_SECONDS, SECONDS).succeeded()

        fun next(): String? = writes.poll(WAIT_SECONDS, SECONDS)
    }

    private fun stream(frames: Sequence<String>, sink: Content.Sink): Pair<Thread, Callback.Completable> {
        val done = Callback.Completable()
        val producer = Thread.ofPlatform().unstarted {
            val writer = FrameWriter(sink, ScheduledExecutorScheduler(), GATHER, null, Thread.currentThread())
            writeFrames(frames.map { it.toByteArray() }, writer, done)
        }
        producer.start()
        return producer to done
    }

    @Test
    fun `frames pulled while a write is in flight go out together in the next write`() {
        val sink = HeldSink()
        val pulledAll = CountDownLatch(1)
        val frames = (1..100).map { "frame $it\n" }
        val (_, done) = stream(
            sequence {
                yieldAll(frames)
                pulledAll.countDown()
            },
            sink,
        )

        pulledAll.await(WAIT_SECONDS, SECONDS) shouldBe true
        sink.release()
        done.get(WAIT_SECONDS, SECONDS)

        sink.writes.toList() shouldBe listOf("", frames.joinToString(""))
    }

    @Test
    fun `a gathered frame goes out when the write ahead of it completes, while the producer is still held`() {
        val sink = HeldSink()
        val askedForMore = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val (_, done) = stream(
            sequence {
                yield("one\n")
                askedForMore.countDown()
                gate.await()
                yield("two\n")
            },
            sink,
        )

        askedForMore.await(WAIT_SECONDS, SECONDS) shouldBe true
        sink.release()
        sink.next() shouldBe ""
        withClue("the frame waited for the producer's next element") { sink.next() shouldBe "one\n" }

        gate.countDown()
        done.get(WAIT_SECONDS, SECONDS)
        sink.next() shouldBe "two\n"
    }

    @Test
    fun `a reader that does not take the first write holds the producer one buffer ahead`() {
        val sink = HeldSink()
        val pulled = AtomicInteger()
        val frame = "x".repeat(FRAME_BYTES - 1) + "\n"
        val count = 50
        val (producer, done) = stream(generateSequence { frame }.take(count).onEach { pulled.incrementAndGet() }, sink)

        awaitParked(producer)
        withClue("a buffer's worth gathered, and the frame that did not fit") {
            pulled.get() shouldBe GATHER / FRAME_BYTES + 1
        }

        sink.release()
        done.get(WAIT_SECONDS, SECONDS)
        val written = sink.writes.toList()
        written.joinToString("") shouldBe frame.repeat(count)
        withClue("a write larger than the buffer: ${written.map { it.length }}") {
            written.all { it.length <= GATHER } shouldBe true
        }
    }

    /** Parked is the claim; the deadline only stops a broken writer hanging the build. */
    private fun awaitParked(thread: Thread) {
        val deadline = System.nanoTime() + SECONDS.toNanos(WAIT_SECONDS)
        while (thread.state != Thread.State.WAITING) {
            if (System.nanoTime() > deadline) fail("the producer never waited: ${thread.state}")
            Thread.onSpinWait()
        }
    }

    private companion object {
        const val GATHER = 1_000
        const val FRAME_BYTES = 100
        const val WAIT_SECONDS = 10L
    }
}
