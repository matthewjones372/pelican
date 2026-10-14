package io.github.matthewjones372.pelican.health

import java.io.IOException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

private const val BYTES_PER_MIB = 1024L * 1024L

/** Fails when the file store holding [path] has less than [minFreeBytes] usable. */
fun diskSpace(path: String, minFreeBytes: Long): Status =
    try {
        // `File.usableSpace` answers 0 for a path that is not there, which
        // would read as a full disk rather than a wrong path.
        val free = Files.getFileStore(Path.of(path)).usableSpace
        if (free >= minFreeBytes) Status.Pass
        else Status.Fail("${mib(free)} free at $path, below ${mib(minFreeBytes)}")
    } catch (e: IOException) {
        Status.Fail("Cannot read the file store at $path: ${e::class.java.simpleName}")
    }

/** Fails when the heap has less than [minFreeBytes] left before its maximum. */
fun heapHeadroom(minFreeBytes: Long): Status {
    val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
    // With no maximum set the heap can grow no further than what is committed.
    val ceiling = if (heap.max < 0) heap.committed else heap.max
    val headroom = ceiling - heap.used
    return if (headroom >= minFreeBytes) Status.Pass
    else Status.Fail("${mib(headroom)} of heap left, below ${mib(minFreeBytes)}")
}

/** Fails naming the threads when any are deadlocked on monitors or locks. Virtual threads are not seen. */
fun noDeadlockedThreads(): Status {
    val threads = ManagementFactory.getThreadMXBean()
    val ids = threads.findDeadlockedThreads() ?: return Status.Pass
    val names = threads.getThreadInfo(ids).filterNotNull().joinToString { it.threadName }
    return Status.Fail("Deadlocked: $names")
}

private fun mib(bytes: Long): String = if (bytes == Long.MAX_VALUE) "unbounded" else "${bytes / BYTES_PER_MIB} MiB"
