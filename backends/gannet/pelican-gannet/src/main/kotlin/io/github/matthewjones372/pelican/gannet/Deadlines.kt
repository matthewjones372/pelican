package io.github.matthewjones372.pelican.gannet

import io.github.matthewjones372.gannet.RequestBody
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** How often the sweeper looks, so a deadline fires up to this late. */
internal const val SWEEP_MILLIS = 250L

/** The body did not arrive in time. Made only when a deadline fires, so it is never one another read could throw. */
internal class TimedOut : IOException("Timed out reading the request body")

/** Marks a deadline whose read finished first. Never thrown. */
private val SETTLED = IOException("settled")

/**
 * One strict read's deadline: armed until the read [settle]s it or a sweep fires it, whichever wins the swap. It is
 * also its own link in the sweeper's list, so arming one allocates nothing else.
 */
internal class Deadline(val dueNanos: Long, val body: RequestBody) : AtomicReference<IOException?>() {
    var next: Deadline? = null

    /** The read is over: the deadline can no longer fire. Returns what it fired with if it already had. */
    fun settle(): IOException? = if (compareAndSet(null, SETTLED)) null else get()
}

/**
 * Strict-body deadlines kept by one thread rather than one scheduled task each.
 *
 * Arming is a push onto a lock-free stack. Every [SWEEP_MILLIS] the sweeper takes the stack, drops what has
 * settled, fails the body of whatever is overdue and keeps the rest, so a settled deadline is held for one sweep at
 * most and only a fired one allocates its exception. A scheduler that removed cancelled tasks would cost every
 * request its lock; one that did not would keep every cancelled task until it fell due.
 */
internal class Sweeper {
    private val arrivals = AtomicReference<Deadline?>()

    /** Read and written by whichever thread sweeps; only one does. */
    private var held: Deadline? = null

    fun arm(body: RequestBody, timeoutMillis: Long, now: Long = System.nanoTime()): Deadline {
        val deadline = Deadline(now + TimeUnit.MILLISECONDS.toNanos(timeoutMillis), body)
        do {
            val head = arrivals.get()
            deadline.next = head
        } while (!arrivals.compareAndSet(head, deadline))
        return deadline
    }

    /** One pass at [now]: returns how many deadlines are still armed, all of them held for the next. */
    fun sweep(now: Long): Int {
        var kept: Deadline? = null
        var count = 0
        for (first in listOf(arrivals.getAndSet(null), held)) {
            var at = first
            while (at != null) {
                val next = at.next
                if (stillArmed(at, now)) {
                    at.next = kept
                    kept = at
                    count++
                } else {
                    // A dropped deadline links to nothing, so it cannot keep a live one reachable from an old region.
                    at.next = null
                }
                at = next
            }
        }
        held = kept
        return count
    }

    private fun stillArmed(deadline: Deadline, now: Long): Boolean = when {
        deadline.get() != null -> false

        now - deadline.dueNanos < 0 -> true

        else -> {
            val timedOut = TimedOut()
            if (deadline.compareAndSet(null, timedOut)) deadline.body.fail(timedOut)
            false
        }
    }

    /** One scheduled task for every deadline, on a daemon thread of its own. */
    fun start(): Sweeper = apply {
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread.ofPlatform().name("pelican-body-deadline").daemon().unstarted(task)
        }.scheduleWithFixedDelay({ sweep(System.nanoTime()) }, SWEEP_MILLIS, SWEEP_MILLIS, TimeUnit.MILLISECONDS)
    }
}

/** Started by the first strict read. */
internal val DEADLINES: Sweeper = Sweeper().start()
