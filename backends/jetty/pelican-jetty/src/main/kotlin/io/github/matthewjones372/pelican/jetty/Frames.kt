package io.github.matthewjones372.pelican.jetty

import io.github.matthewjones372.pelican.SseOutput
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.util.BufferUtil
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.IteratingCallback
import org.eclipse.jetty.util.thread.Scheduler
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * Writes a frame at once when nothing is in flight, and gathers the frames pulled while a write is in flight into
 * the next one, up to [gather] bytes, past which the producer waits. A `Sequence` cannot say whether its next
 * element is ready, so readiness is never guessed: what is gathered goes out the moment the write ahead of it
 * completes, never on a later pull, and a frame followed by a long wait is not held back by the wait.
 *
 * A keep-alive fills a gap of [keepAlive] with nothing in flight. A failed write means the client has gone, so it
 * interrupts [producer], which may be parked waiting for an element.
 */
internal class FrameWriter(
    private val sink: Content.Sink,
    private val scheduler: Scheduler,
    private val gather: Int,
    private val keepAlive: Duration?,
    private val producer: Thread,
) {
    private val lock = ReentrantLock()
    private val settled = lock.newCondition()
    private val flusher = Flusher()

    // Guarded by lock. Invariant: anything gathered implies a write in flight, which sends it when it completes.
    private var inFlight = false
    private var first: ByteArray? = null
    private var rest: ByteArrayOutputStream? = null
    private var failure: Throwable? = null

    @Volatile
    private var stopped = false
    private var idle: Scheduler.Task? = null

    /** Sends the status and headers before the first element is asked for, as Pekko does. */
    fun commit() = write(ByteArray(0))

    fun write(frame: ByteArray) {
        lock.withLock {
            while (inFlight && gathered() >= gather && failure == null) settled.await()
            failure?.let { throw it }
            add(frame)
            if (inFlight) return
            inFlight = true
        }
        flusher.iterate()
    }

    /** Ends the body once everything handed over has been written. */
    fun finish(callback: Callback) {
        lock.withLock {
            while (inFlight) settled.await()
            failure?.let { throw it }
        }
        stop()
        sink.write(true, BufferUtil.EMPTY_BUFFER, callback)
    }

    /** Waits out the write in flight, so the response is failed only once nothing is pending on it. */
    fun abandon() {
        stop()
        lock.withLock { while (inFlight) settled.awaitUninterruptibly() }
    }

    private fun gathered(): Int = rest?.size() ?: first?.size ?: 0

    private fun add(frame: ByteArray) {
        val head = first
        when {
            head == null -> first = frame

            rest == null -> rest = ByteArrayOutputStream(gather).also {
                it.writeBytes(head)
                it.writeBytes(frame)
            }

            else -> rest?.writeBytes(frame)
        }
    }

    private fun take(): ByteArray? {
        val batch = rest?.toByteArray() ?: first
        first = null
        rest = null
        return batch
    }

    /** Jetty's own loop over one pending write at a time, which also keeps a write that completes inline flat. */
    private inner class Flusher : IteratingCallback() {
        override fun process(): Action {
            val batch = lock.withLock {
                take().also {
                    if (it == null) inFlight = false
                    settled.signalAll()
                }
            }
            if (batch == null) {
                scheduleKeepAlive()
                return Action.IDLE
            }
            cancelKeepAlive()
            sink.write(false, ByteBuffer.wrap(batch), this)
            return Action.SCHEDULED
        }

        override fun onCompleteFailure(cause: Throwable) {
            lock.withLock {
                failure = cause
                inFlight = false
                take()
                settled.signalAll()
            }
            stop()
            producer.interrupt()
        }
    }

    private fun stop() {
        stopped = true
        cancelKeepAlive()
    }

    @Synchronized
    private fun cancelKeepAlive() {
        idle?.cancel()
        idle = null
    }

    @Synchronized
    private fun scheduleKeepAlive() {
        if (keepAlive == null || stopped) return
        idle?.cancel()
        idle = scheduler.schedule(::fill, keepAlive.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

    /** On the scheduler. Anything in flight means the stream is not idle, and the keep-alive reschedules after it. */
    private fun fill() {
        lock.withLock {
            if (inFlight || stopped || failure != null) return
            add(KEEP_ALIVE)
            inFlight = true
        }
        flusher.iterate()
    }
}

private val KEEP_ALIVE = SseOutput.KEEP_ALIVE_FRAME.toByteArray()
